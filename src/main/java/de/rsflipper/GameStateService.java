package de.rsflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.rsflipper.model.FillEvent;
import de.rsflipper.model.OfferSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.WorldType;

/**
 * GameStateService (SPEC §4.2): hält den AccountStatus aus RuneLite-Events zusammen —
 * GP, Inventar, GE-Slots, Fill-Deltas, Buy-Limits. Alle Methoden werden vom ClientThread
 * aufgerufen (Events), gelesen wird der fertige JSON-Payload threadsicher.
 */
@Slf4j
@Singleton
public class GameStateService
{
	private static final int GE_SLOTS = 8;

	private final Client client;
	@Getter
	private final BuyLimitTracker buyLimits;
	@Getter
	private final FillOutbox outbox;

	// Live-Sicht der Slots für den AccountStatus-Payload.
	private final GrandExchangeOffer[] lastOffers = new GrandExchangeOffer[GE_SLOTS];
	// Persistierter Slot-Zustand (Platte) — Vergleichsbasis der Fill-Erkennung,
	// funktioniert damit auch über Client-Neustarts hinweg (Offline-Fills, §4.5).
	@Getter
	private final SlotStateStore slotStore;

	// Fills innerhalb dieses Fensters nach Login gelten als Login-Abgleich (login=true):
	// Profit ja, Live-Preisdaten nein (Zeitpunkt unbekannt).
	private static final long LOGIN_WINDOW_MS = 10_000;
	private long loginAt;

	// Aktuelle Server-Suggestion — Basis für Highlight, Prefill und
	// suggestedPriceUsed-Erkennung (§4.3.6).
	private volatile de.rsflipper.model.ClientSuggestion currentSuggestion;
	private volatile boolean lastSetupOpen;
	private final boolean[] slotSuggestedPrice = new boolean[GE_SLOTS];
	// Skip = temporäre Client-Sperre (10 min) — wird mit der Blocklist gemerged (§4.6).
	private static final long SKIP_MS = 10 * 60_000;
	private final Map<Integer, Long> tempSkips = new HashMap<>();
	// Vorschlags-Gedächtnis: itemId -> [preis, ts]. Der suggestedPriceUsed-Abgleich
	// läuft gegen die letzten 3 min, nicht nur den aktuellen Vorschlag — sonst
	// markiert die schnelle Rotation (nächster Vorschlag lädt sofort) frisch
	// platzierte Offers fälschlich als manuell (Pegasian-Bug 2026-07-17).
	private static final long SUGGESTION_MEMORY_MS = 3 * 60_000;
	// Pro Item ALLE Vorschlagspreise der letzten 3 min (die Preisleiter ändert den Preis
	// zwischen Syncs leicht — nur der letzte Preis erzeugte falsche Manuell-Flags, 2026-07-18).
	private final Map<Integer, java.util.List<long[]>> recentSuggestions = new HashMap<>();

	private long gp;
	private final List<int[]> inventory = new ArrayList<>(); // [itemId, qty]
	private boolean dirty = true;
	// Dringend = Offer-Ereignis (Fill/Abschluss): Sync überspringt die Drossel,
	// damit die nächste Aktion in <1 s statt ~3 s vorgeschlagen wird.
	private boolean urgent;

	@Inject
	public GameStateService(Client client, com.google.gson.Gson gson)
	{
		this.client = client;
		this.buyLimits = new BuyLimitTracker(gson);
		this.outbox = new FillOutbox(gson);
		this.slotStore = new SlotStateStore(gson);
	}

	private long initializedAccount = Long.MIN_VALUE;

	public void onLogin(long accountHash)
	{
		// RuneLite meldet LOGGED_IN auch nach jedem Regionswechsel/Ladebildschirm —
		// nur beim ECHTEN Account-Wechsel/Erst-Login initialisieren, sonst würde die
		// Slot-Sicht ständig geleert (Fund 2026-07-17: Server sah nur 0-2 von 8 Offers).
		if (accountHash == initializedAccount)
		{
			return;
		}
		initializedAccount = accountHash;
		buyLimits.load(accountHash);
		outbox.load(accountHash);
		// Persistierte Slot-Zustände laden — die Login-Sync-Events werden gegen diese
		// Basislinie verglichen; Differenzen sind echte (Offline-)Fills.
		slotStore.load(accountHash);
		loginAt = System.currentTimeMillis();
		for (int i = 0; i < GE_SLOTS; i++)
		{
			lastOffers[i] = null;
		}
		dirty = true;
	}

	public void onOfferChanged(int slot, GrandExchangeOffer offer)
	{
		if (slot < 0 || slot >= GE_SLOTS)
		{
			return;
		}
		GrandExchangeOfferState state = offer.getState();
		long now = System.currentTimeMillis();
		boolean empty = state == GrandExchangeOfferState.EMPTY;

		// Während Login-Übergängen feuert der Client EMPTY-Events für alle Slots —
		// die sind kein echtes "Slot geleert" und dürfen die Basislinie nicht löschen.
		if (empty && client.getGameState() != net.runelite.api.GameState.LOGGED_IN)
		{
			return;
		}

		SlotStateStore.SavedSlot prev = slotStore.get(slot);
		SlotStateStore.SavedSlot cur = empty ? null : new SlotStateStore.SavedSlot(
			offer.getItemId(), offer.getPrice(), offer.getTotalQuantity(),
			offer.getQuantitySold(), offer.getSpent(), state.name(),
			prev != null && prev.getItemId() == offer.getItemId() && prev.getPrice() == offer.getPrice()
				? prev.isSuggestedPriceUsed() : slotSuggestedPrice[slot]);

		// Duplikat: exakt gleicher Zustand wie persistiert → nichts zu tun.
		if (cur != null && cur.equals(prev))
		{
			lastOffers[slot] = offer;
			return;
		}

		// Fill-Delta gegen den PERSISTIERTEN Zustand — erkennt auch Fills, die während
		// der Offline-Zeit passierten (gleicher Offer: Item + Preis identisch).
		if (cur != null && prev != null
			&& prev.getItemId() == cur.getItemId() && prev.getPrice() == cur.getPrice())
		{
			int tradedDelta = cur.getQuantityTraded() - prev.getQuantityTraded();
			long spentDelta = cur.getSpent() - prev.getSpent();
			if (tradedDelta > 0)
			{
				boolean isBuy = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
					|| state == GrandExchangeOfferState.CANCELLED_BUY;
				long avgPrice = spentDelta > 0 ? spentDelta / tradedDelta : offer.getPrice();
				// Innerhalb des Login-Fensters erkannt = Offline-Fill: Profit ja, Live-Preis nein.
				boolean isLoginFill = now - loginAt < LOGIN_WINDOW_MS;
				outbox.add(new FillEvent(java.util.UUID.randomUUID().toString(), now,
					offer.getItemId(), isBuy ? "buy" : "sell", tradedDelta, avgPrice, isLoginFill));
				if (isBuy)
				{
					buyLimits.recordBuy(offer.getItemId(), tradedDelta, now);
				}
				log.debug("Fill: slot={} item={} {}x{} à {}{}", slot, offer.getItemId(),
					isBuy ? "buy" : "sell", tradedDelta, avgPrice, isLoginFill ? " (Login-Abgleich)" : "");
			}
		}

		// suggestedPriceUsed (§4.3.6): Neues Offer, dessen Item + Preis unserem aktuellen
		// Vorschlag entsprechen → der User ist dem Vorschlag gefolgt. Server behandelt
		// nur solche Offers als "seine" (Re-Price erlaubt); manuelle bleiben unangetastet.
		boolean newOffer = cur != null && (prev == null || prev.getItemId() != cur.getItemId() || prev.getPrice() != cur.getPrice());
		if (newOffer)
		{
			java.util.List<long[]> recent = recentSuggestions.get(offer.getItemId());
			boolean matched = false;
			if (recent != null)
			{
				for (long[] entry : recent)
				{
					if (entry[0] == offer.getPrice() && now - entry[1] < SUGGESTION_MEMORY_MS)
					{
						matched = true;
						break;
					}
				}
			}
			slotSuggestedPrice[slot] = matched;
			cur = new SlotStateStore.SavedSlot(cur.getItemId(), cur.getPrice(), cur.getTotalQuantity(),
				cur.getQuantityTraded(), cur.getSpent(), cur.getState(), slotSuggestedPrice[slot]);
		}
		else if (empty)
		{
			slotSuggestedPrice[slot] = false;
		}
		else if (prev != null && cur != null)
		{
			// Flag aus Persistenz übernehmen (überlebt Client-Neustarts).
			slotSuggestedPrice[slot] = cur.isSuggestedPriceUsed();
		}

		slotStore.put(slot, cur);
		lastOffers[slot] = offer;
		dirty = true;
		urgent = true;
	}

	// Positions-Abgleich (Ramon 2026-07-22): Items mit offener Server-Position — beim
	// naechsten Bank-Oeffnen melden wir deren Bank-Bestaende (0 = nicht in der Bank).
	private volatile java.util.Set<Integer> positionItems = java.util.Collections.emptySet();
	private final Map<Integer, Integer> pendingBankCounts = new java.util.concurrent.ConcurrentHashMap<>();
	private volatile boolean bankSnapshotPending = false;

	public void setPositionItems(java.util.Set<Integer> items)
	{
		this.positionItems = items;
	}

	public void onBankSnapshot(ItemContainer bank)
	{
		java.util.Set<Integer> wanted = positionItems;
		if (wanted.isEmpty())
		{
			return;
		}
		Map<Integer, Integer> counts = new HashMap<>();
		for (Integer id : wanted)
		{
			counts.put(id, 0); // explizite 0 = "nicht in der Bank" (entscheidend fuer den Abgleich)
		}
		for (Item item : bank.getItems())
		{
			if (item.getId() > 0 && counts.containsKey(item.getId()))
			{
				counts.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		pendingBankCounts.clear();
		pendingBankCounts.putAll(counts);
		bankSnapshotPending = true;
		markDirty();
	}

	public void onInventoryChanged(ItemContainer container)
	{
		gp = 0;
		inventory.clear();
		// Aggregieren + Noted-Normalisierung (Ramon-Fund 2026-07-18): 5 Tranchen-Boots
		// = 5 Slot-Einträge, Zertifikate = eigene Item-ID — der Server braucht EINE
		// Zeile pro echtem Item mit Gesamtmenge, sonst wird 'verkaufe alle' zu 'x1'.
		Map<Integer, Integer> agg = new HashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() == ItemID.COINS_995)
			{
				gp += item.getQuantity();
			}
			else if (item.getId() > 0)
			{
				int id = item.getId();
				net.runelite.api.ItemComposition def = client.getItemDefinition(id);
				if (def != null && def.getNote() != -1)
				{
					id = def.getLinkedNoteId(); // Zertifikat → echtes Item
				}
				agg.merge(id, item.getQuantity(), Integer::sum);
			}
		}
		for (Map.Entry<Integer, Integer> e : agg.entrySet())
		{
			inventory.add(new int[]{e.getKey(), e.getValue()});
		}
		dirty = true;
	}

	public boolean consumeDirty()
	{
		boolean d = dirty;
		dirty = false;
		return d;
	}

	public void markDirty()
	{
		dirty = true;
	}

	/** Sofort-Sync beim nächsten Tick (~600ms) — für User-Aktionen wie den
	 *  Modify-Backout, die keine Offer-Events erzeugen (Ramon 2026-07-18). */
	public void markUrgent()
	{
		dirty = true;
		urgent = true;
	}

	public boolean consumeUrgent()
	{
		boolean u = urgent;
		urgent = false;
		return u;
	}

	public void setCurrentSuggestion(de.rsflipper.model.ClientSuggestion s)
	{
		currentSuggestion = s;
		if (s != null && s.isActionable() && s.getItemId() > 0 && s.getPrice() > 0)
		{
			long nowMs = System.currentTimeMillis();
			java.util.List<long[]> list = recentSuggestions.computeIfAbsent(s.getItemId(), k -> new java.util.ArrayList<>());
			list.removeIf(e -> nowMs - e[1] > SUGGESTION_MEMORY_MS);
			list.add(new long[]{s.getPrice(), nowMs});
		}
	}

	/** Vom Server quittierte Fills aus der Outbox entfernen (§4.5, at-least-once + Dedup). */
	public void applyAcks(JsonObject response)
	{
		if (response.has("ackedFillIds") && response.get("ackedFillIds").isJsonArray())
		{
			java.util.List<String> ids = new ArrayList<>();
			response.getAsJsonArray("ackedFillIds").forEach(e -> ids.add(e.getAsString()));
			outbox.ack(ids);
		}
	}

	// M12a Dump-Alert-Override: Ein aktiver Alert verdraengt die normale Suggestion
	// fuer max. 90 s — bis der User handelt (aktive Order auf dem Item), ein neuer
	// Sync ihn abloest oder die Zeit ablaeuft. Prinzip: Alert ersetzt den Vorschlag.
	private static final long DUMP_OVERRIDE_MS = 90_000;
	private volatile de.rsflipper.model.ClientSuggestion dumpSuggestion;
	private volatile long dumpSuggestionTs;

	public void setDumpSuggestion(de.rsflipper.model.ClientSuggestion s)
	{
		dumpSuggestion = s;
		dumpSuggestionTs = System.currentTimeMillis();
	}

	public boolean hasActiveDump()
	{
		return activeDump() != null;
	}

	public void clearDumpSuggestion()
	{
		dumpSuggestion = null;
	}

	private de.rsflipper.model.ClientSuggestion activeDump()
	{
		de.rsflipper.model.ClientSuggestion d = dumpSuggestion;
		if (d == null)
		{
			return null;
		}
		boolean placed = false;
		for (net.runelite.api.GrandExchangeOffer o : client.getGrandExchangeOffers() != null
			? client.getGrandExchangeOffers() : new net.runelite.api.GrandExchangeOffer[0])
		{
			if (o != null && o.getItemId() == d.getItemId()
				&& o.getState() != net.runelite.api.GrandExchangeOfferState.EMPTY)
			{
				placed = true;
				break;
			}
		}
		if (placed || System.currentTimeMillis() - dumpSuggestionTs > DUMP_OVERRIDE_MS)
		{
			dumpSuggestion = null;
			return null;
		}
		return d;
	}

	/** Freier GE-Slot vorhanden? (Dump-Alert-Guard) */
	public boolean hasEmptySlot()
	{
		net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return false;
		}
		for (net.runelite.api.GrandExchangeOffer o : offers)
		{
			if (o == null || o.getState() == net.runelite.api.GrandExchangeOfferState.EMPTY)
			{
				return true;
			}
		}
		return false;
	}

	public de.rsflipper.model.ClientSuggestion getCurrentSuggestion()
	{
		de.rsflipper.model.ClientSuggestion d = activeDump();
		return d != null ? d : currentSuggestion;
	}

	/** Zuletzt gemeldeter freier GP-Bestand (fuer den Debug-Sim-Button). */
	public long getGp()
	{
		return gp;
	}

	/** Potenzieller Gewinn je Verkaufs-Slot (vom Server, §4.6): boxId -> gp. */
	/** §4.6 Slot-HUD: Profit (nullable), Order-Alter und ETA je belegtem Slot. */
	public static final class SlotHud
	{
		public final Long profit;      // Sell: Netto vs. Einstand | Buy: prospektiv (gelb)
		public final boolean buySide;  // Kauforder → gelbe Plakette (Ramon 2026-07-18)
		public final long ageSecAtSync;
		public final double etaMin;    // -1 = unbekannt
		// Break-even-Geduld (Ramon 2026-07-21): Restzeit der Halte-Regel in Sekunden,
		// -1 = Regel greift nicht. Sichtbar am Slot, sonst wirkt er haengengeblieben.
		public final long holdRemainSecAtSync;
		public final long receivedAt;  // fürs Live-Hochzählen des Alters

		public SlotHud(Long profit, boolean buySide, long ageSecAtSync, double etaMin, long holdRemainSecAtSync)
		{
			this.profit = profit;
			this.buySide = buySide;
			this.ageSecAtSync = ageSecAtSync;
			this.etaMin = etaMin;
			this.holdRemainSecAtSync = holdRemainSecAtSync;
			this.receivedAt = System.currentTimeMillis();
		}

		public long liveAgeSec()
		{
			return ageSecAtSync + (System.currentTimeMillis() - receivedAt) / 1000;
		}

		/** Live herunterzaehlende Geduld-Restzeit; 0 = abgelaufen/inaktiv. */
		public long liveHoldRemainSec()
		{
			if (holdRemainSecAtSync < 0)
			{
				return 0;
			}
			return Math.max(0, holdRemainSecAtSync - (System.currentTimeMillis() - receivedAt) / 1000);
		}
	}

	private volatile Map<Integer, SlotHud> slotHud = new HashMap<>();

	public void setSlotHud(Map<Integer, SlotHud> hud)
	{
		this.slotHud = hud;
	}

	public Map<Integer, SlotHud> getSlotHud()
	{
		return slotHud;
	}

	/** Skip-Button (§4.6): Item 10 min lang nicht mehr vorschlagen (clientseitig). */
	public void skipItem(int itemId)
	{
		if (itemId > 0)
		{
			tempSkips.put(itemId, System.currentTimeMillis() + SKIP_MS);
			dirty = true;
		}
	}

	/** Sell-Skip (Ramon 2026-07-19): Verkaufs-Vorschlag 3 min nicht erneut bringen. */
	private static final long SELL_SKIP_MS = 3 * 60_000;
	private final java.util.Map<Integer, Long> sellSkips = new java.util.concurrent.ConcurrentHashMap<>();

	public void skipSell(int itemId)
	{
		if (itemId > 0)
		{
			sellSkips.put(itemId, System.currentTimeMillis() + SELL_SKIP_MS);
			dirty = true;
		}
	}

	// Nie-geblockt-Prinzip (Ramon 2026-07-21): Skip auf Kauf-Wartung (modify_buy/abort)
	// legt das Offer 3 min still, statt das Item zu blocken — der alte Weg (blockedItems)
	// erzwang den Abort erst recht, weil ein geblocktes Item nie mehr "stillGood" war.
	private final java.util.Map<Integer, Long> maintSkips = new java.util.concurrent.ConcurrentHashMap<>();

	public void skipMaintenance(int itemId)
	{
		if (itemId > 0)
		{
			maintSkips.put(itemId, System.currentTimeMillis() + SELL_SKIP_MS);
			dirty = true;
		}
	}

	/** Baut den AccountStatus-Payload (SPEC §12.2) inkl. gepufferter Fill-Events (§12.4a). */
	public JsonObject buildAccountStatus(String profile, String activity, String blockedCsv, boolean sellOnly, boolean paused,
		int targetEtfMin, int minProfitGp, boolean useP2p, int dumpMinProfit, int reservedSlots)
	{
		long now = System.currentTimeMillis();
		JsonObject o = new JsonObject();
		o.addProperty("profile", profile);
		o.addProperty("activity", activity);
		o.addProperty("sellOnly", sellOnly);
		o.addProperty("targetEtfMin", targetEtfMin);
		o.addProperty("minProfitGp", Math.max(0, minProfitGp));
		o.addProperty("paused", paused);

		// Blocklist (Config) + aktive Skips (temporär) mergen (§4.6).
		JsonArray blocked = new JsonArray();
		for (String part : blockedCsv.split(","))
		{
			try
			{
				blocked.add(Integer.parseInt(part.trim()));
			}
			catch (NumberFormatException ignored)
			{
				// leere/ungültige Einträge überspringen
			}
		}
		tempSkips.entrySet().removeIf(e -> e.getValue() < now);
		tempSkips.keySet().forEach(blocked::add);
		o.add("blockedItems", blocked);
		com.google.gson.JsonArray skippedSells = new com.google.gson.JsonArray();
		sellSkips.entrySet().removeIf(e -> e.getValue() < now);
		sellSkips.keySet().forEach(skippedSells::add);
		o.add("sellSkips", skippedSells);
		com.google.gson.JsonArray skippedMaint = new com.google.gson.JsonArray();
		maintSkips.entrySet().removeIf(e -> e.getValue() < now);
		maintSkips.keySet().forEach(skippedMaint::add);
		o.add("maintSkips", skippedMaint);
		// Positions-Abgleich: Bank-Snapshot EINMAL mitschicken, dann verwerfen.
		if (bankSnapshotPending)
		{
			com.google.gson.JsonArray bankArr = new com.google.gson.JsonArray();
			for (Map.Entry<Integer, Integer> e : pendingBankCounts.entrySet())
			{
				com.google.gson.JsonObject b = new com.google.gson.JsonObject();
				b.addProperty("itemId", e.getKey());
				b.addProperty("qty", e.getValue());
				bankArr.add(b);
			}
			o.add("bankCounts", bankArr);
			bankSnapshotPending = false;
		}
		o.addProperty("accountHash", client.getAccountHash());
		// §4.3.6-Flow: welches Item gerade im GE-Setup offen ist (Abbruch-Erkennung
		// des Modify-Flows serverseitig). Doppelter Fund 2026-07-18 (Antler guard /
		// Dragon harpoon): (1) Die CURRENT_GE_ITEM-Varp behält nach dem Zurück-Klick
		// den letzten Wert; (2) das Setup-Widget (465,26) gilt auch in der Slot-
		// Übersicht als sichtbar. Zuverlässig ist NUR Varbit 4439 (im Setup geöffneter
		// Slot, 0 = keiner) — dasselbe Signal wie beim Slot-Menü-Tausch.
		boolean setupOpen = client.getVarbitValue(4439) != 0;
		this.lastSetupOpen = setupOpen; // Platzierungs-Schutz-Cache (Client-Riegel, 2026-07-19)
		o.addProperty("geSetupItem", setupOpen ? client.getVarpValue(net.runelite.api.VarPlayer.CURRENT_GE_ITEM) : -1);
		o.addProperty("geSetupOpen", setupOpen); // Platzierungs-Schutz: auch SUCH-Phase (Item noch 0/stale) friert ein
		o.addProperty("useP2p", useP2p); // Free-Tier-Opt-in (Checkbox unter der Suggestion-Box)
		// M12a Dump-Alerts: Schwelle + reservierte Slots + GE-offen (Zustell-Gate).
		o.addProperty("dumpMinProfit", dumpMinProfit);
		o.addProperty("reservedSlots", reservedSlots);
		o.addProperty("geOpen", geInterfaceOpen);
		o.addProperty("displayName", client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null);
		o.addProperty("isMember", client.getWorldType().contains(WorldType.MEMBERS));
		o.addProperty("gp", gp);
		o.addProperty("ts", now);

		JsonArray inv = new JsonArray();
		for (int[] it : inventory)
		{
			JsonObject e = new JsonObject();
			e.addProperty("itemId", it[0]);
			e.addProperty("qty", it[1]);
			inv.add(e);
		}
		o.add("inventory", inv);

		// Aus dem persistierten Slot-Zustand bauen — die Live-Offer-Objekte werden von
		// RuneLite mutiert und die Sicht bei Regionswechseln geleert (Fund 2026-07-17).
		JsonArray offers = new JsonArray();
		for (int slot = 0; slot < GE_SLOTS; slot++)
		{
			SlotStateStore.SavedSlot ss = slotStore.get(slot);
			if (ss == null || "EMPTY".equals(ss.getState()))
			{
				continue;
			}
			boolean active = "BUYING".equals(ss.getState()) || "SELLING".equals(ss.getState());
			JsonObject e = new JsonObject();
			e.addProperty("boxId", slot);
			e.addProperty("state", ss.getState());
			e.addProperty("itemId", ss.getItemId());
			e.addProperty("price", ss.getPrice());
			e.addProperty("totalQuantity", ss.getTotalQuantity());
			e.addProperty("quantityTraded", ss.getQuantityTraded());
			e.addProperty("spent", ss.getSpent());
			e.addProperty("active", active);
			e.addProperty("suggestedPriceUsed", ss.isSuggestedPriceUsed());
			offers.add(e);
		}
		o.add("offers", offers);

		// Outbox NICHT leeren — Fills bleiben, bis der Server sie quittiert (§4.5).
		JsonArray fills = new JsonArray();
		for (FillEvent f : outbox.snapshot())
		{
			JsonObject e = new JsonObject();
			e.addProperty("id", f.getId());
			e.addProperty("ts", f.getTs());
			e.addProperty("itemId", f.getItemId());
			e.addProperty("side", f.getSide());
			e.addProperty("qtyDelta", f.getQtyDelta());
			e.addProperty("avgPrice", f.getAvgPrice());
			e.addProperty("login", f.isLogin());
			fills.add(e);
		}
		o.add("fillEvents", fills);

		JsonObject limits = new JsonObject();
		buyLimits.allUsed(now).forEach((itemId, used) -> limits.addProperty(String.valueOf(itemId), used));
		o.add("buyLimitsUsed", limits);

		return o;
	}

	/** Platzierungs-Schutz: war das GE-Setup beim letzten Sync offen? (Thread-sicherer Cache) */
	public boolean isSetupOpen()
	{
		return lastSetupOpen;
	}

	// GE-Interface sichtbar? Vom Client-Thread je GameTick gepflegt — der Key-Handler
	// (AWT-Thread) darf keine Widgets abfragen (Chat-Spam-Fix 2026-07-19: der Widget-
	// Zugriff aus keyPressed brach den kompletten Event-Durchlauf inkl. Skip).
	private volatile boolean geInterfaceOpen;

	public void updateGeInterfaceOpen(boolean open)
	{
		this.geInterfaceOpen = open;
	}

	public boolean isGeInterfaceOpen()
	{
		return geInterfaceOpen;
	}

	/** Stale-Modify-Riegel (Pegasian-Fund 2026-07-19): Ist im Slot boxId noch eine
	 *  AKTIVE Order fuer itemId? Antwort aus dem LIVE-Client-Stand — der Server
	 *  rechnet auf dem Stand des Requests und kann einen Fill um Sekunden verpassen. */
	public boolean hasActiveOffer(int boxId, int itemId)
	{
		if (boxId < 0 || boxId >= lastOffers.length)
		{
			return false;
		}
		GrandExchangeOffer o = lastOffers[boxId];
		if (o == null || o.getItemId() != itemId)
		{
			return false;
		}
		GrandExchangeOfferState st = o.getState();
		return st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
	}
}

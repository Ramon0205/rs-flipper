package de.rsflipper;

import com.google.inject.Provides;
import de.rsflipper.api.ApiClient;
import de.rsflipper.model.ClientSuggestion;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "RS-Flipper",
	description = "Live GE flipping suggestions, real-time dump alerts and gp-exact profit tracking",
	tags = {"flipping", "grand exchange", "ge", "merching", "money making"}
)
public class RSFlipperPlugin extends Plugin
{
	/** Sync-Takt: alle 5 Ticks (~3 s) bei Änderungen, Heartbeat alle 50 Ticks (~30 s). */
	private static final int SYNC_TICKS = 5;
	private static final int HEARTBEAT_TICKS = 50;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private RSFlipperConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private GameStateService gameState;

	@Inject
	private RSFlipperOverlay overlay;

	@Inject
	private OfferPrefill prefill;

	@Inject
	private GeSearchSuggestion searchSuggestion;

	@Inject
	private net.runelite.client.input.KeyManager keyManager;

	@Inject
	private ApiClient api;

	@Inject
	private de.rsflipper.api.AuthService auth;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

	@Inject
	private net.runelite.client.Notifier notifier;

	@Inject
	private PrefsSync prefsSync;

	private volatile long sessionStart;

	private void startSession()
	{
		if (sessionStart == 0)
		{
			sessionStart = System.currentTimeMillis();
			panel.setSessionStart(sessionStart);
			refreshStats();
		}
	}

	@Subscribe
	public void onWidgetLoaded(net.runelite.api.events.WidgetLoaded event)
	{
		if (event.getGroupId() == 465)
		{
			startSession(); // erste GE-Öffnung = Session-Beginn
		}
	}

	/** §4.6 UI v2: Stats für die gewählte Periode laden (itemsSince filtert die Item-Liste). */
	private void refreshStats()
	{
		long hash = client.getAccountHash();
		if (hash == -1)
		{
			return;
		}
		long now = System.currentTimeMillis();
		java.util.Calendar cal = java.util.Calendar.getInstance();
		cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
		cal.set(java.util.Calendar.MINUTE, 0);
		cal.set(java.util.Calendar.SECOND, 0);
		cal.set(java.util.Calendar.MILLISECOND, 0);
		long itemsSince;
		switch (panel.getSelectedPeriodLabel())
		{
			case "Today": itemsSince = cal.getTimeInMillis(); break;
			case "Week": itemsSince = now - 7L * 86_400_000; break;
			case "Month": itemsSince = now - 30L * 86_400_000; break;
			case "All time": itemsSince = 0; break;
			default: itemsSince = sessionStart > 0 ? sessionStart : now; break; // Session
		}
		long since = sessionStart > 0 ? sessionStart : now; // Session noch nicht gestartet → leer
		// Tagesgrenze = LOKALE Mitternacht des Clients (Zeitzonen-Fund 2026-07-19:
		// Server-Mitternacht ist UTC -> 'Today' verlor die Fruehsession 00:00-02:00).
		api.getStats(config.serverUrl(), hash, since, itemsSince, cal.getTimeInMillis(), panel::showStats,
			err -> log.debug("Stats: {}", err));
	}

	@Inject
	private net.runelite.client.callback.ClientThread clientThread;

	private RSFlipperPanel panel;
	private NavigationButton navButton;
	private int ticksSinceSync;
	private int dumpPollTicks;

	@Override
	protected void startUp()
	{
		panel = new RSFlipperPanel(this::onSkip, this::onBlock, gameState::markDirty, config, configManager, auth, () -> config.serverUrl());
		// Session startet erst mit der ERSTEN GE-Öffnung (Ramon 2026-07-18) — 0 = wartet.
		sessionStart = 0;
		panel.setStatsRefresher(this::refreshStats);
		panel.setSessionStart(0);
		panel.setOnResetSession(() -> {
			sessionStart = 0;
			panel.setSessionStart(0);
			clientThread.invokeLater(() -> {
				// GE gerade offen? → Session sofort neu starten, sonst auf nächste Öffnung warten.
				net.runelite.api.widgets.Widget ge = client.getWidget(465, 0);
				if (ge != null && !ge.isHidden())
				{
					startSession();
				}
			});
			refreshStats();
		});
		panel.setDumpSimulator(this::simulateDumpAlert);
		panel.setFeedbackSender((category, message) -> clientThread.invokeLater(() -> {
			// Client-Zugriffe (getLocalPlayer) NUR im Client-Thread — der Dialog-Callback
			// laeuft auf dem Swing-EDT (Feedback-Bug-Fund 2026-07-20: Request kam nie ab).
			com.google.gson.JsonObject ctx = new com.google.gson.JsonObject();
			ctx.addProperty("source", "plugin");
			// RSN nur mitschicken, wenn wirklich einer da ist — Feedback geht auch
			// komplett ohne Login/Charakter (Ramon 2026-07-20), dann bleibt er leer.
			if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
			{
				ctx.addProperty("rsn", client.getLocalPlayer().getName());
			}
			api.postFeedback(config.serverUrl(), category, message, ctx,
				ok -> panel.setStatus(ok ? "Thanks - feedback sent!" : "Could not send feedback - try again later"));
		}));
		panel.setStatus("Warte auf Login …");

		navButton = NavigationButton.builder()
			.tooltip("RS-Flipper")
			.icon(createIcon())
			.priority(5)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		overlayManager.add(overlay);
		keyManager.registerKeyListener(prefill);
		prefill.setSkipHandler(() -> {
			ClientSuggestion s = gameState.getCurrentSuggestion();
			if (s != null)
			{
				onSkip(s);
			}
		});
		log.info("RS-Flipper gestartet (M4)");
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(prefill);
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		panel = null;
		navButton = null;
	}

	private void onSkip(ClientSuggestion s)
	{
		String t = s.getType();
		if ("sell".equals(t) || "modify_sell".equals(t))
		{
			// Sell-Skip (Ramon 2026-07-19): 3 min Ruhe fuer dieses Item — Markt
			// beruhigen lassen, andere Slots arbeiten weiter. Stop-Loss bleibt scharf.
			gameState.skipSell(s.getItemId());
			panel.setStatus("Sell skipped: " + s.getItemName() + " (3 min)");
		}
		else
		{
			gameState.skipItem(s.getItemId());
			panel.setStatus("Skipped: " + s.getItemName() + " (10 min)");
		}
		gameState.clearDumpSuggestion(); // uebersprungener Dump-Alert verschwindet sofort
		ticksSinceSync = SYNC_TICKS; // nächster Tick holt sofort den nächsten Vorschlag
	}

	private void onBlock(ClientSuggestion s)
	{
		String current = config.blockedItems();
		String updated = current == null || current.isEmpty() ? String.valueOf(s.getItemId()) : current + "," + s.getItemId();
		configManager.setConfiguration(RSFlipperConfig.GROUP, "blockedItems", updated);
		panel.setStatus("Blockiert: " + s.getItemName());
		gameState.markDirty();
		ticksSinceSync = SYNC_TICKS;
	}

	@Subscribe
	public void onConfigChanged(net.runelite.client.events.ConfigChanged e)
	{
		// Phase 3: lokale Settings-Aenderung -> gedebouncter Push (PrefsSync filtert Keys).
		prefsSync.onLocalChange(e.getGroup(), e.getKey());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			gameState.onLogin(client.getAccountHash());
			panel.setStatus("Eingeloggt — synchronisiere …");
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		// GE-Setup geöffnet/geschlossen (Varbit 4439) → Sofort-Sync: Der Modify-
		// Backout wird damit in ~1s erkannt statt erst beim 30s-Regelsync
		// (Aranea-Fund 2026-07-18: 'buy 3x' stand nach dem Zurück-Klick bis zu
		// 40s in der Anzeige, obwohl der Server längst richtig entschieden hätte).
		if (event.getVarbitId() == 4439)
		{
			gameState.markUrgent(); // dirty allein wartete den Regel-Takt ab (~3s Latenz)
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		gameState.onOfferChanged(e.getSlot(), e.getOffer());
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() == InventoryID.INVENTORY.getId())
		{
			gameState.onInventoryChanged(e.getItemContainer());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		// GE-Ablehnung als Buy-Limit-Korrektursignal (SPEC §12.3).
		if (e.getType() == ChatMessageType.GAMEMESSAGE && e.getMessage().contains("buying limit"))
		{
			log.debug("Buy-Limit-Meldung erkannt: {}", e.getMessage());
		}
	}

	/**
	 * Slot-Aktions-Swap (§4.4, Verhalten nach §16a-Analyse, eigener Code):
	 * Steht für einen Slot ein abort/modify an, werden alle Menü-Einträge dieses
	 * Slots AUSSER der Zielaktion deprioritisiert — die Zielaktion ("Abort offer" /
	 * "Modify offer") wird damit zum Linksklick-Standard. Reine Menü-Umsortierung:
	 * der eine bewusste Klick bleibt beim Spieler (§1.5).
	 */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (config.passiveMode() || !config.slotActionSwap())
		{
			return;
		}
		ClientSuggestion s = gameState.getCurrentSuggestion();
		if (s == null || s.getBoxId() < 0)
		{
			return;
		}
		boolean abort = "abort".equals(s.getType());
		boolean modify = s.getType() != null && s.getType().startsWith("modify");
		if (!abort && !modify)
		{
			return;
		}
		// Nur in der GE-Übersicht (kein Slot-Detail offen, Varbit 4439 = 0).
		if (client.getVarbitValue(4439) != 0)
		{
			return;
		}
		net.runelite.api.widgets.Widget slot = client.getWidget(465, config.geSlotChildBase() + s.getBoxId());
		if (slot == null || event.getActionParam1() != slot.getId())
		{
			return;
		}
		String target = abort ? "Abort offer" : "Modify offer";
		if (!target.equals(event.getOption()))
		{
			event.getMenuEntry().setDeprioritized(true);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		// GE-offen-Cache fuer den Key-Handler (AWT-Thread, siehe GameStateService).
		net.runelite.api.widgets.Widget geRoot = client.getWidget(465, 0);
		gameState.updateGeInterfaceOpen(geRoot != null && !geRoot.isHidden());
		if (!config.passiveMode())
		{
			prefill.tick();
			searchSuggestion.tick();
		}
		// M12a Schnell-Poll fuer Dump-Alerts (Ramon 2026-07-19): entkoppelt vom grossen
		// Sync — bei offener GE alle 2 Ticks (~1,2 s) ein leichter /dump/poll, damit
		// Alerts fast ohne Verzoegerung ankommen (Racing gegen andere Flipper).
		if (!config.passiveMode() && config.dumpMinProfit() > 0
			&& gameState.isGeInterfaceOpen() && auth.isLoggedIn())
		{
			if (++dumpPollTicks >= 2)
			{
				dumpPollTicks = 0;
				api.getDumpAlert(config.serverUrl(), config.dumpMinProfit(), gameState.getGp(),
					resp -> clientThread.invokeLater(() -> handleDumpAlert(resp)));
			}
		}
		ticksSinceSync++;
		boolean urgent = gameState.consumeUrgent();
		boolean dirty = gameState.consumeDirty();
		// Offer-Ereignisse (Fill/Abschluss/Abbruch) syncen SOFORT — die Drossel gilt
		// nur für unkritische Änderungen (Latenz-Fund 2026-07-17: ~3 s bis zur
		// Folgeaktion nach einem Fill — Ziel: gefühlt sofortiges Umschalten).
		if (urgent || (dirty && ticksSinceSync >= SYNC_TICKS) || ticksSinceSync >= HEARTBEAT_TICKS)
		{
			ticksSinceSync = 0;
			sync();
		}
		else if (dirty)
		{
			gameState.markDirty();
		}
	}

	/** M12a Dump-Alert: Alert wird zur aktiven Suggestion, wenn das
	 *  Feature an ist, ein GE-Slot frei ist und kein Alert in Bearbeitung haengt. */
	private long lastDumpTs;

	private void handleDumpAlert(com.google.gson.JsonObject response)
	{
		if (config.dumpMinProfit() <= 0
			|| !response.has("dumpAlert") || !response.get("dumpAlert").isJsonObject())
		{
			return;
		}
		com.google.gson.JsonObject a = response.getAsJsonObject("dumpAlert");
		long ts = a.has("ts") ? a.get("ts").getAsLong() : 0;
		if (ts <= lastDumpTs)
		{
			return; // derselbe Alert haengt an mehreren Syncs
		}
		if (!gameState.hasEmptySlot() || gameState.hasActiveDump())
		{
			return;
		}
		// Menge auf den LIVE-Kapitalstand kappen (Ramon-Fund 2026-07-19: echter Alert
		// schlug 3 Stueck vor, die nicht bezahlbar waren — der Server rechnet gegen die
		// gp des Requests, der Client kennt den aktuellen Stand). Reicht es nicht mal
		// fuer 1 Stueck: gar kein Alert (ts NICHT merken, damit ein spaeterer Sync mit
		// mehr Gold ihn noch zeigen kann).
		long price = a.get("price").getAsLong();
		long affordable = price > 0 ? gameState.getGp() / price : 0;
		long amount = Math.min(a.get("amount").getAsLong(), affordable);
		if (amount < 1)
		{
			return;
		}
		long predSell = a.get("predSell").getAsLong();
		a.addProperty("amount", amount);
		a.addProperty("netProfit", Math.round((0.98 * predSell - price) * amount));
		lastDumpTs = ts;
		applyDumpAlert(a);
	}

	/** Debug (Ramon 2026-07-19): simulierter Dump zum Feature-Test ohne echten Markt-Dump.
	 *  Bewusst OHNE Guards (Feature-Schalter/freier Slot) — es soll immer etwas zu sehen geben. */
	void simulateDumpAlert()
	{
		long price = 2500;
		long predSell = 3300;
		// Menge realistisch aufs freie Kapital kappen (wie der Server) — sonst
		// schlaegt der Sim eine Order vor, fuer die das Geld nicht reicht.
		long affordable = gameState.getGp() / price;
		long amount = Math.max(1, Math.min(7500, affordable));
		long profit = Math.round((0.98 * predSell - price) * amount);
		com.google.gson.JsonObject a = new com.google.gson.JsonObject();
		a.addProperty("itemId", net.runelite.api.ItemID.DRAGON_BONES);
		a.addProperty("itemName", "Dragon bones");
		a.addProperty("price", price);
		a.addProperty("predSell", predSell);
		a.addProperty("amount", amount);
		a.addProperty("netProfit", profit);
		a.addProperty("extreme", false);
		applyDumpAlert(a);
	}

	private void applyDumpAlert(com.google.gson.JsonObject a)
	{
		long price = a.get("price").getAsLong();
		long predSell = a.get("predSell").getAsLong();
		int amount = a.get("amount").getAsInt();
		long netProfit = a.get("netProfit").getAsLong();
		boolean extreme = a.has("extreme") && a.get("extreme").getAsBoolean();
		com.google.gson.JsonObject sug = new com.google.gson.JsonObject();
		sug.addProperty("type", "buy");
		sug.addProperty("boxId", -1);
		sug.addProperty("itemId", a.get("itemId").getAsInt());
		sug.addProperty("itemName", a.has("itemName") && !a.get("itemName").isJsonNull()
			? a.get("itemName").getAsString() : "?");
		sug.addProperty("price", price);
		sug.addProperty("quantity", amount);
		sug.addProperty("message", "DUMP ALERT: buy " + amount + "x at "
			+ String.format("%,d", price) + " gp - predicted sell " + String.format("%,d", predSell)
			+ " gp (~" + String.format("%,d", netProfit) + " gp)"
			+ (extreme ? " [extreme dump - verify fill]" : ""));
		com.google.gson.JsonObject why = new com.google.gson.JsonObject();
		why.addProperty("potenziellerProfit", netProfit);
		why.addProperty("dumpAlert", true);
		sug.add("why", why);
		de.rsflipper.model.ClientSuggestion dump = de.rsflipper.model.ClientSuggestion.from(sug);
		gameState.setDumpSuggestion(dump);
		gameState.markDirty();
		final String dumpName = dump.getItemName();
		if (config.dumpSound())
		{
			clientThread.invokeLater(() -> {
				// Zwei Wege fuer Zuverlaessigkeit (Ramon-Fund 2026-07-19: In-Game-Ding
				// blieb stumm, wenn die Spiel-Soundeffekte leise/aus sind): RuneLite-
				// Notifier (eigene Lautstaerke + Taskbar-Blink) UND der GE-Ding.
				notifier.notify("Dump alert: " + dumpName);
				client.playSoundEffect(net.runelite.api.SoundEffectID.GE_ADD_OFFER_DINGALING);
			});
		}
	}

	private void sync()
	{
		com.google.gson.JsonObject status = gameState.buildAccountStatus(
			"aggressiv", // Baseline fuer alle (Ramon 2026-07-19); Profil-Auswahl spaeter ggf. wieder
			config.activity().wire(),
			config.blockedItems() == null ? "" : config.blockedItems(),
			panel.isSellOnly(),
			panel.isPaused(),
			config.targetEtf().minutes(),
			config.minProfitGp(),
			config.useP2p(),
			config.dumpMinProfit(),
			// Alerts aktiv => IMMER genau 1 Slot reserviert (Ramon 2026-07-19, keine Auswahl).
			config.dumpMinProfit() > 0 ? 1 : 0
		);
		long gp = status.get("gp").getAsLong();
		api.postSuggestion(
			config.serverUrl(),
			status,
			response -> {
				ClientSuggestion s = ClientSuggestion.from(response);
				// Platzierungs-Schutz-Riegel (Webweaver-Fund 2026-07-19): Ist das GE-Setup
				// offen, wird KEIN abweichender neuer Vorschlag angezeigt — der Server haelt
				// ohnehin ein; das hier faengt nur die Sub-Sekunden-Race ab (Sync feuerte
				// vor dem Kaufen-Klick, Antwort kommt an, waehrend das Fenster schon offen ist).
				ClientSuggestion cur = gameState.getCurrentSuggestion();
				// Setup offen => Anzeige KOMPLETT stabil (auch kein Preis-Update desselben
				// Items — sonst leuchten die Highlights nach der Eingabe wieder auf).
				boolean raceBlock = gameState.isSetupOpen() && cur != null;
				// Stale-Modify-Riegel (Pegasian-Fund 2026-07-19): Der Server rechnete auf
				// dem Request-Stand — ist die referenzierte Order inzwischen gefuellt/weg,
				// den Vorschlag verwerfen und sofort frisch syncen.
				String sugType = s.getType();
				boolean staleModify = ("modify_buy".equals(sugType) || "modify_sell".equals(sugType) || "abort".equals(sugType))
					&& !gameState.hasActiveOffer(s.getBoxId(), s.getItemId());
				if (staleModify)
				{
					gameState.markUrgent();
					gameState.markDirty();
				}
				else if (!raceBlock)
				{
					gameState.setCurrentSuggestion(s);
				}
				gameState.applyAcks(response);
				handleDumpAlert(response);
				// Phase 3: Server-Prefs neuer als lokal? -> pull (neuester Wert gewinnt).
				if (response.has("prefsUpdatedAtMs") && !response.get("prefsUpdatedAtMs").isJsonNull())
				{
					prefsSync.maybePull(response.get("prefsUpdatedAtMs").getAsLong());
				}
				// Tier-/Kontingent-Anzeige (Ramon 2026-07-19).
				panel.setAccountInfo(response.has("account") && response.get("account").isJsonObject()
					? response.getAsJsonObject("account") : null);

				// Slot-Gewinne + realisierte Profite (Animation) aus der Antwort (§4.6).
				java.util.Map<Integer, GameStateService.SlotHud> slotHud = new java.util.HashMap<>();
				if (response.has("slotInfo") && response.get("slotInfo").isJsonArray())
				{
					response.getAsJsonArray("slotInfo").forEach(e -> {
						com.google.gson.JsonObject o = e.getAsJsonObject();
						Long profit = o.has("potentialProfit") && !o.get("potentialProfit").isJsonNull()
							? o.get("potentialProfit").getAsLong() : null;
						boolean buySide = o.has("side") && "buy".equals(o.get("side").getAsString());
						long ageSec = o.has("ageSec") && !o.get("ageSec").isJsonNull() ? o.get("ageSec").getAsLong() : 0;
						double etaMin = o.has("etaMin") && !o.get("etaMin").isJsonNull() ? o.get("etaMin").getAsDouble() : -1;
						long holdSec = o.has("patienceRemainSec") && !o.get("patienceRemainSec").isJsonNull()
							? o.get("patienceRemainSec").getAsLong() : -1;
						slotHud.put(o.get("boxId").getAsInt(), new GameStateService.SlotHud(profit, buySide, ageSec, etaMin, holdSec));
					});
				}
				gameState.setSlotHud(slotHud);
				if (response.has("flipResults") && response.get("flipResults").isJsonArray())
				{
					response.getAsJsonArray("flipResults").forEach(e -> {
						com.google.gson.JsonObject o = e.getAsJsonObject();
						overlay.addProfitPopup(o.get("itemId").getAsInt(), o.get("profit").getAsLong());
					});
					if (response.getAsJsonArray("flipResults").size() > 0)
					{
						refreshStats(); // Profit-Sektion sofort aktuell (§4.6 UI v2)
					}
				}

				// EINE Wahrheit fuer Panel UND Overlay (Dump-Bug 2026-07-19): Das Panel
				// zeigte die rohe Sync-Suggestion (z.B. modify), waehrend das Overlay den
				// aktiven Dump-Override renderte (buy, tuerkiser leerer Slot). Beide
				// zeigen jetzt die EFFEKTIVE Suggestion inkl. Override.
				final ClientSuggestion effective = gameState.getCurrentSuggestion() != null
					? gameState.getCurrentSuggestion() : s;
				clientThread.invokeLater(() -> {
					javax.swing.Icon icon = null;
					// Collect nach fertigem VERKAUF: Muenzhaufen (groesste Stufe) statt Item-Icon (Ramon 2026-07-19).
					boolean coinIcon = "collect".equals(effective.getType()) && effective.getCollectProfit() != Long.MIN_VALUE;
					if (coinIcon)
					{
						net.runelite.client.util.AsyncBufferedImage img = itemManager.getImage(net.runelite.api.ItemID.COINS_995, 10_000, false);
						icon = new javax.swing.ImageIcon(img);
						img.onLoaded(() -> panel.repaint());
					}
					else if (effective.getItemId() > 0)
					{
						net.runelite.client.util.AsyncBufferedImage img = itemManager.getImage(effective.getItemId());
						icon = new javax.swing.ImageIcon(img);
						img.onLoaded(() -> panel.repaint());
					}
					panel.showSuggestion(effective, icon);
				});
				// §4.3.8: Datenalter transparent machen (Kill-Switch-Kriterium: >5 min = keine Buys).
				String age = "";
				if (response.has("marketAgeSec") && !response.get("marketAgeSec").isJsonNull())
				{
					long ageSec = response.get("marketAgeSec").getAsLong();
					age = ageSec > 300 ? String.format(" | DATA %d min OLD!", ageSec / 60) : String.format(" | data %ds", ageSec);
				}
				panel.setStatus(String.format("Connected - %,d gp%s %s", gp,
					age, panel.isPaused() ? "(paused)" : ""));
			},
			error -> {
				if ("AUTH_401".equals(error))
				{
					// M9: Access-Token abgelaufen -> Refresh; der naechste Sync nutzt das neue Token.
					auth.tryRefresh(config.serverUrl());
					panel.setStatus(auth.isLoggedIn() ? "Renewing session ..." : "Please log in (Settings tab)");
				}
				else
				{
					panel.setStatus("⚠ " + error);
				}
			}
		);
	}

	@Provides
	RSFlipperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RSFlipperConfig.class);
	}

	// Platzhalter-Icon, bis ein richtiges Logo existiert (§18.3): "RF" auf dunklem Grund.
	/** Sidebar-Icon = Website-Favicon (Ramon 2026-07-19): dunkler Emerald-Kreis
	 *  mit gruenem Ring und Aufwaerts-Pfeil — identisch zu app/icon.svg, nur
	 *  zur Laufzeit gezeichnet statt als Ressource gebundelt. */
	private static BufferedImage createIcon()
	{
		// Neues Logo (Ramon 2026-07-21): Favicon-Ressource statt Laufzeit-Zeichnung.
		return net.runelite.client.util.ImageUtil.loadImageResource(RSFlipperPlugin.class, "icon16.png");
	}
}

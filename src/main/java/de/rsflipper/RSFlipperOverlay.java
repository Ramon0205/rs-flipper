package de.rsflipper;

import de.rsflipper.model.ClientSuggestion;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.VarPlayer;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Aktions-Highlighting (SPEC §4.4, Konzept nach §16a-Analyse,
 * eigene Optik/Implementierung). Führt den User rein visuell — der Klick bleibt
 * beim Spieler (Slot anklicken = natives OSRS-Verhalten, §1.5):
 *   COLLECT (fertig)    → grün     | ABORT              → rot
 *   MODIFY (Preis/Menge)→ orange   | BUY/SELL (neu)     → türkis
 * Sell-Vorschläge markieren zusätzlich das Item im Inventar.
 */
public class RSFlipperOverlay extends Overlay
{
	private static final int GE_GROUP_ID = 465;
	private static final int GE_INV_GROUP = 467; // Inventar bei geöffneter GE
	private static final int INV_GROUP = 149;    // Standard-Inventar

	private static final int SETUP_CHILD = 26;
	private static final int SETUP_QTY_ALL_BUTTON = 50; // "All" — bei Tranchen-Sells (Ramon 2026-07-18)
	private static final int SETUP_QTY_BUTTON = 51;
	private static final int SETUP_PRICE_BUTTON = 54;
	private static final int SETUP_CONFIRM_BUTTON = 58;
	private static final int VARBIT_SETUP_QUANTITY = 4396;
	private static final int VARBIT_SETUP_PRICE = 4398;

	private static final Color GREEN = new Color(46, 204, 113);
	private static final Color ORANGE = new Color(255, 152, 31);
	private static final Color RED = new Color(231, 76, 60);
	private static final Color TEAL = new Color(26, 188, 156);
	/** Break-even-Geduld-Plakette (Ramon 2026-07-21): bewusst NICHT Orange (= Modify). */
	private static final Color AMBER = new Color(255, 196, 60);

	private final Client client;
	private final GameStateService gameState;
	private final RSFlipperConfig config;
	private final OfferPrefill prefill;
	private final net.runelite.client.game.ItemManager itemManager;
	private volatile java.awt.image.BufferedImage coinImage; // einzelne Goldmünze (Item 995)

	/** Fly-up-Profit-Animation (§4.6): realisierte Sell-Profite steigen 2,2 s auf und verblassen. */
	private static final long POPUP_MS = 3100;
	private static final class ProfitPopup
	{
		final int itemId;
		final long profit;
		final long startMs = System.currentTimeMillis();

		ProfitPopup(int itemId, long profit)
		{
			this.itemId = itemId;
			this.profit = profit;
		}
	}
	private final java.util.concurrent.CopyOnWriteArrayList<ProfitPopup> popups = new java.util.concurrent.CopyOnWriteArrayList<>();

	public void addProfitPopup(int itemId, long profit)
	{
		popups.add(new ProfitPopup(itemId, profit));
	}

	private final GeSearchSuggestion searchSuggestion;

	@Inject
	RSFlipperOverlay(Client client, GameStateService gameState, RSFlipperConfig config, OfferPrefill prefill,
		net.runelite.client.game.ItemManager itemManager, GeSearchSuggestion searchSuggestion)
	{
		this.client = client;
		this.gameState = gameState;
		this.config = config;
		this.prefill = prefill;
		this.itemManager = itemManager;
		this.searchSuggestion = searchSuggestion;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (config.passiveMode())
		{
			return null;
		}
		// Dump-Suchzeile glatt pulsieren (Ramon 2026-07-19) — per Frame wie der Slot.
		searchSuggestion.animate();
		// Hotkey-Hinweis bei offener GE-Zahleneingabe: Zeile im Look der Such-Suggestion,
		// FEST ueber der Chatbox verankert — kein Springen mehr (Ramon 2026-07-19).
		int nextY = 40;
		boolean applyRowDrawn = false;
		long pending = prefill.getPendingValue();
		if (pending > 0)
		{
			Widget chatbox = client.getWidget(ComponentID.CHATBOX_CONTAINER);
			if (chatbox != null && !chatbox.isHidden())
			{
				drawApplyRow(g, pending, chatbox.getBounds());
				applyRowDrawn = true;
			}
		}

		// §4.6 Manuelle-Trade-Hilfe (Ramon 2026-07-18): optimale Kauf-/Verkaufspreise
		// fürs offene GE-Setup — auch ohne Engine-Vorschlag (normales Spielen).
		int quoteSetupItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (!applyRowDrawn && quoteSetupItem > 0 && quoteSetupItem == prefill.getQuoteItemId()
			&& prefill.getQuoteBuy() > 0 && prefill.getQuoteSell() > 0)
		{
			String quote = String.format("Optimal:  buy %,d  |  sell %,d",
				prefill.getQuoteBuy(), prefill.getQuoteSell());
			g.setColor(new Color(0, 0, 0, 170));
			int qw = g.getFontMetrics().stringWidth(quote) + 16;
			g.fillRoundRect(8, nextY, qw, 22, 6, 6);
			g.setColor(TEAL);
			g.drawString(quote, 16, nextY + 15);
		}

		drawSlotHud(g);
		drawProfitPopups(g);

		ClientSuggestion s = gameState.getCurrentSuggestion();
		if (s == null || !s.isActionable())
		{
			return null;
		}
		// M12a: Dump-Alerts pulsieren ROT (Ramon 2026-07-19) — maximale Aufmerksamkeit.
		boolean dump = s.isDumpAlert();

		// Sell: Item im Inventar hervorheben — Klick startet das Sell-Offer.
		if ("sell".equals(s.getType()))
		{
			highlightInventoryItem(g, s.getItemId(), TEAL);
		}

		// Offer-SETUP-Screen: Menge/Preis/Bestätigen gezielt markieren (orange = ändern nötig).
		Widget setup = client.getWidget(GE_GROUP_ID, SETUP_CHILD);
		int setupItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (setup != null && !setup.isHidden() && setupItem == s.getItemId())
		{
			int curQty = client.getVarbitValue(VARBIT_SETUP_QUANTITY);
			int curPrice = client.getVarbitValue(VARBIT_SETUP_PRICE);
			boolean isModify = s.getType().startsWith("modify");
			boolean qtyOk = isModify || curQty == s.getQuantity();
			boolean priceOk = curPrice == s.getPrice();
			if (!qtyOk)
			{
				// Verhalten nach §16a-Analyse (eigene Implementierung): Beim SELL
				// mit Inventarbestand == Zielmenge (Tranchen-Käufe, ungestackte Items) den
				// "All"-Button markieren — ein Klick verkauft alles, kein Zahlentippen.
				boolean sellAll = "sell".equals(s.getType())
					&& inventoryCount(s.getItemId()) == s.getQuantity();
				highlightChildMaybePulse(g, setup, sellAll ? SETUP_QTY_ALL_BUTTON : SETUP_QTY_BUTTON, dump ? RED : ORANGE, dump);
			}
			if (!priceOk)
			{
				highlightChildMaybePulse(g, setup, SETUP_PRICE_BUTTON, dump ? RED : ORANGE, dump);
			}
			if (qtyOk && priceOk)
			{
				highlightChildMaybePulse(g, setup, SETUP_CONFIRM_BUTTON, dump ? RED : GREEN, dump);
			}
			return null;
		}

		// GE-Übersicht: passenden Slot in der Aktions-Farbe markieren.
		Widget geMain = client.getWidget(GE_GROUP_ID, config.geSlotChildBase());
		if (geMain == null || geMain.isHidden())
		{
			return null;
		}
		int boxId = s.getBoxId();
		Color color;
		switch (s.getType())
		{
			case "collect":
				// Ein Klick statt drei: der Sammel-Button oben rechts (465/6, Kind 2)
				// holt ALLE fertigen Offers auf einmal (ein Klick statt drei, §16a).
				Widget topBar = client.getWidget(GE_GROUP_ID, 6);
				Widget collectBtn = topBar != null ? topBar.getChild(2) : null;
				if (collectBtn != null && !collectBtn.isHidden())
				{
					drawBox(g, collectBtn.getBounds(), GREEN);
					return null;
				}
				color = GREEN; // Fallback: Slot markieren, falls Button nicht auffindbar
				break;
			case "abort":
				color = RED;
				break;
			case "modify_buy":
			case "modify_sell":
				color = ORANGE;
				break;
			case "buy":
				color = dump ? RED : TEAL;
				if (boxId < 0)
				{
					boxId = firstEmptySlot();
				}
				break;
			default:
				return null; // sell: nur Inventar (oben)
		}
		if (boxId < 0)
		{
			return null;
		}
		Widget slot = client.getWidget(GE_GROUP_ID, config.geSlotChildBase() + boxId);
		if (slot != null && !slot.isHidden())
		{
			if (dump)
			{
				drawBoxPulse(g, slot.getBounds(), RED);
			}
			else
			{
				drawBox(g, slot.getBounds(), color);
			}
		}
		return null;
	}

	/** Apply-Zeile bei offener Zahleneingabe (Ramon 2026-07-19): exakt der Look der
	 *  Such-Suggestion — dunkler Emerald-Grund, gruene Kontur, "RS-Flipper" bold in
	 *  Markengruen, Wert weiss, "[ ENTER ]  apply" grau rechts. Fest ueber der Chatbox. */
	private void drawApplyRow(Graphics2D g, long pending, Rectangle cb)
	{
		// Unter der Eingabezeile — ungefaehr auf der Hoehe der Such-Suggestion-Zeile,
		// damit alles an derselben Stelle erscheint (Ramon 2026-07-19).
		int h = 24;
		int x = cb.x + 6;
		int w = cb.width - 12;
		// OBERHALB der Frage-Zeile ("Set a price ..."), oben in der Box — gleiche
		// Hoehe wie die Item-Suggestion-Zeile der GE-Suche (Ramon 2026-07-19).
		int y = cb.y + 12;
		Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
		if (title != null && !title.isHidden())
		{
			Rectangle tb = title.getBounds();
			y = Math.max(cb.y + 6, tb.y - h - 8);
		}
		if (y + h > cb.y + cb.height - 4)
		{
			y = cb.y + cb.height - h - 4;
		}

		g.setColor(new Color(14, 26, 21, 215));
		g.fillRoundRect(x, y, w, h, 6, 6);
		g.setColor(new Color(16, 185, 129));
		g.setStroke(new BasicStroke(1.5f));
		g.drawRoundRect(x, y, w, h, 6, 6);

		java.awt.FontMetrics fm = g.getFontMetrics();
		int baseline = y + (h + fm.getAscent() - fm.getDescent()) / 2;

		// Pixelschrift rendert BOLD doppelt/verschmiert — daher alles in Normalschnitt.
		g.setColor(new Color(16, 185, 129));
		g.drawString("RS-Flipper", x + 8, baseline);
		int labelW = fm.stringWidth("RS-Flipper");

		String value = String.format("%,d", pending);
		g.setColor(Color.WHITE);
		g.drawString(value, x + 8 + labelW + 10, baseline);

		String key = "[ " + RSFlipperPanel.keybindText(config.fillHotkey()) + " ]  apply";
		int kw = fm.stringWidth(key);
		g.setColor(new Color(154, 165, 160));
		g.drawString(key, x + w - kw - 8, baseline);

		// Preis-Eingabe: Optimal-Quote mittig in der Zeile statt als eigene Box (Ramon 2026-07-19).
		boolean priceInput = title != null && title.getText() != null
			&& title.getText().startsWith("Set a price");
		int setupItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (priceInput && setupItem > 0 && setupItem == prefill.getQuoteItemId()
			&& prefill.getQuoteBuy() > 0 && prefill.getQuoteSell() > 0)
		{
			String optimal = String.format("Optimal:  buy %,d  |  sell %,d",
				prefill.getQuoteBuy(), prefill.getQuoteSell());
			int ow = fm.stringWidth(optimal);
			g.setColor(TEAL);
			g.drawString(optimal, x + (w - ow) / 2, baseline);
		}
	}

	private int firstEmptySlot()
	{
		net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			for (int i = 0; i < offers.length; i++)
			{
				if (offers[i] == null || offers[i].getState() == net.runelite.api.GrandExchangeOfferState.EMPTY)
				{
					return i;
				}
			}
		}
		return -1;
	}

	/** Gesamtstückzahl eines Items im Inventar — Tranchen-Einzelslots UND Zertifikate
	 *  (noted, eigene ID) zählen zusammen (Ramon 2026-07-18). */
	private int inventoryCount(int itemId)
	{
		net.runelite.api.ItemContainer inv = client.getItemContainer(net.runelite.api.InventoryID.INVENTORY);
		if (inv == null)
		{
			return 0;
		}
		int total = 0;
		for (net.runelite.api.Item item : inv.getItems())
		{
			if (item.getId() <= 0)
			{
				continue;
			}
			int id = item.getId();
			net.runelite.api.ItemComposition def = client.getItemDefinition(id);
			if (def != null && def.getNote() != -1)
			{
				id = def.getLinkedNoteId();
			}
			if (id == itemId)
			{
				total += item.getQuantity();
			}
		}
		return total;
	}

	private void highlightInventoryItem(Graphics2D g, int itemId, Color color)
	{
		Widget inv = client.getWidget(GE_INV_GROUP, 0);
		if (inv == null || inv.isHidden())
		{
			inv = client.getWidget(INV_GROUP, 0);
		}
		if (inv == null || inv.isHidden())
		{
			return;
		}
		Widget[] items = inv.getDynamicChildren();
		if (items == null)
		{
			return;
		}
		for (Widget item : items)
		{
			if (item == null || item.isHidden())
			{
				continue;
			}
			// Zertifikate (noted) tragen eine eigene Item-ID → auf das echte Item
			// normalisieren, sonst bleibt der Stack unmarkiert (Ramon 2026-07-18).
			int id = item.getItemId();
			if (id > 0)
			{
				net.runelite.api.ItemComposition def = client.getItemDefinition(id);
				if (def != null && def.getNote() != -1)
				{
					id = def.getLinkedNoteId();
				}
			}
			if (id == itemId)
			{
				drawBox(g, item.getBounds(), color);
			}
		}
	}

	private void highlightChild(Graphics2D g, Widget parent, int childIdx, Color color)
	{
		Widget child = parent.getChild(childIdx);
		if (child != null && !child.isHidden())
		{
			drawBox(g, child.getBounds(), color);
		}
	}

	/** §4.6 Slot-HUD (Ramon 2026-07-18): Profit-Plakette auf dem Fortschrittsbalken
	 *  (dunkler Hintergrund → lesbar auf grün/gelb/rot), Order-Alter links und
	 *  ETA-Vorhersage rechts der Buy/Sell-Zeile. */
	private void drawSlotHud(Graphics2D g)
	{
		java.util.Map<Integer, GameStateService.SlotHud> hud = gameState.getSlotHud();
		if (hud.isEmpty())
		{
			return;
		}
		for (java.util.Map.Entry<Integer, GameStateService.SlotHud> e : hud.entrySet())
		{
			Widget slot = client.getWidget(GE_GROUP_ID, config.geSlotChildBase() + e.getKey());
			if (slot == null || slot.isHidden())
			{
				continue;
			}
			Rectangle b = slot.getBounds();
			GameStateService.SlotHud info = e.getValue();

			// Layout (Ramon 2026-07-18): Alter/ETA auf der oberen Buy/Sell-Leiste;
			// Profit-Plakette auf dem FORTSCHRITTSBALKEN — dem schmalen Streifen
			// direkt ÜBER dem Preis am Slot-Boden (nicht die obere Leiste!).
			int lineY = b.y + 16;
			if (info.profit != null)
			{
				long p = info.profit;
				String text = (p >= 0 ? "+" : "-") + formatGp(Math.abs(p));
				int tw = g.getFontMetrics().stringWidth(text);
				int x = b.x + (b.width - tw) / 2;
				int rectY = b.y + b.height - 36; // Balken-Streifen über der Preiszeile
				int rectH = 16;
				g.setColor(new Color(0, 0, 0, 185)); // Plakette: lesbar auf jeder Balkenfarbe
				g.fillRoundRect(x - 4, rectY, tw + 8, rectH, 6, 6);
				// Vertikal exakt zentriert (Font-Metriken statt Augenmaß).
				java.awt.FontMetrics fm = g.getFontMetrics();
				int baseline = rectY + (rectH + fm.getAscent() - fm.getDescent()) / 2;
				// Kauforders: GELB = prospektiver Profit (liegt auf dem Tisch, noch nicht
				// kurz bevor); Sells: grün/rot wie gehabt (Ramon 2026-07-18).
				g.setColor(info.buySide ? new Color(255, 240, 0) : (p >= 0 ? GREEN : RED)); // sattes Gelb (Ramon)
				g.drawString(text, x, baseline);
			}

			String age = formatClock(info.liveAgeSec());
			g.setColor(Color.BLACK);
			g.drawString(age, b.x + 6, lineY + 1);
			g.setColor(new Color(200, 200, 200));
			g.drawString(age, b.x + 5, lineY);
			// Break-even-Geduld (Ramon 2026-07-21): Der Slot HAELT bewusst — ohne die
			// Plakette wirkt er haengengeblieben. "hold 32m" = Regel aktiv, Restzeit
			// zaehlt live herunter; die ETA entfaellt solange (sie gilt fuer den
			// Markt-Anker, nicht fuer den gehaltenen Break-even-Preis).
			long holdSec = info.liveHoldRemainSec();
			if (holdSec > 0)
			{
				String hold = "hold " + formatDuration(holdSec);
				int htw = g.getFontMetrics().stringWidth(hold);
				int hx = b.x + b.width - htw - 5;
				java.awt.FontMetrics hfm = g.getFontMetrics();
				g.setColor(new Color(0, 0, 0, 185));
				g.fillRoundRect(hx - 4, lineY - hfm.getAscent(), htw + 8, hfm.getAscent() + hfm.getDescent(), 6, 6);
				g.setColor(AMBER);
				g.drawString(hold, hx, lineY);
			}
			else if (info.etaMin >= 0)
			{
				String eta = "~" + formatDuration(Math.round(info.etaMin * 60));
				int etw = g.getFontMetrics().stringWidth(eta);
				g.setColor(Color.BLACK);
				g.drawString(eta, b.x + b.width - etw - 4, lineY + 1);
				g.setColor(TEAL);
				g.drawString(eta, b.x + b.width - etw - 5, lineY);
			}
		}
	}

	/** Hochzählender Timer im mm:ss-Format (Ramon 2026-07-18). */
	private static String formatClock(long sec)
	{
		return String.format("%d:%02d", sec / 60, sec % 60);
	}

	/** Kompakte Dauer: 45s, 12m, 1h5m. */
	private static String formatDuration(long sec)
	{
		if (sec < 60)
		{
			return sec + "s";
		}
		long min = sec / 60;
		if (min < 60)
		{
			return min + "m";
		}
		return (min / 60) + "h" + (min % 60 > 0 ? (min % 60) + "m" : "");
	}

	/** Fly-up-Animation für realisierte Profite (§4.6, aufgewertet 2026-07-18):
	 *  Pop-Einstieg, Ease-Out-Steigflug, fette Kontur-Schrift, Goldmünze — Belohnung! */
	private void drawProfitPopups(Graphics2D g)
	{
		long now = System.currentTimeMillis();
		for (ProfitPopup p : popups)
		{
			long age = now - p.startMs;
			if (age > POPUP_MS)
			{
				popups.remove(p);
				continue;
			}
			float t = age / (float) POPUP_MS;
			// Fade erst im letzten Drittel; Pop-Skalierung in den ersten 150ms.
			int alpha = t < 0.66f ? 255 : (int) (255 * (1 - (t - 0.66f) / 0.34f));
			float pop = t < 0.14f ? 1.5f - (t / 0.14f) * 0.5f : 1.0f; // etwas dramatischer
			float rise = 1 - (1 - t) * (1 - t); // Ease-Out
			Rectangle anchor = findAnchor(p.itemId);
			int cx = anchor.x + anchor.width / 2;
			int y = anchor.y - (int) (60 * rise);

			String text = (p.profit >= 0 ? "+" : "-") + formatGp(Math.abs(p.profit));
			Color c = p.profit >= 0 ? GREEN : RED;
			java.awt.Font base = g.getFont();
			g.setFont(base.deriveFont(java.awt.Font.BOLD, 25f * pop)); // 1,5x (Ramon)
			java.awt.FontMetrics fm = g.getFontMetrics();
			int tw = fm.stringWidth(text);

			// Goldmünze links vom Betrag (Item 995, Einzelmünze).
			java.awt.image.BufferedImage coin = coinImage;
			if (coin == null)
			{
				coinImage = itemManager.getImage(net.runelite.api.ItemID.COINS_995, 1, false);
				coin = coinImage;
			}
			int coinW = coin != null ? 57 : 0; // mit der Schrift mitgewachsen (1,5x)
			int startX = cx - (tw + coinW + 4) / 2;
			if (coin != null)
			{
				java.awt.Composite oldComp = g.getComposite();
				g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha / 255f));
				g.drawImage(coin, startX, y - fm.getAscent() / 2 - 22, 57, 51, null);
				g.setComposite(oldComp);
			}

			int tx = startX + coinW + 4;
			// Kontur (4 Richtungen) für den satten Look + Lesbarkeit auf allem.
			g.setColor(new Color(0, 0, 0, alpha));
			g.drawString(text, tx - 1, y);
			g.drawString(text, tx + 1, y);
			g.drawString(text, tx, y - 1);
			g.drawString(text, tx, y + 1);
			g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
			g.drawString(text, tx, y);
			g.setFont(base);
		}
	}

	/** Anker der Animation: Slot mit dem Item, sonst oberer GE-Bereich, sonst Bildschirm oben. */
	private Rectangle findAnchor(int itemId)
	{
		net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			for (int i = 0; i < offers.length; i++)
			{
				if (offers[i] != null && offers[i].getItemId() == itemId
					&& offers[i].getState() != net.runelite.api.GrandExchangeOfferState.EMPTY)
				{
					Widget slot = client.getWidget(GE_GROUP_ID, config.geSlotChildBase() + i);
					if (slot != null && !slot.isHidden())
					{
						return slot.getBounds();
					}
				}
			}
		}
		Widget ge = client.getWidget(GE_GROUP_ID, 6);
		if (ge != null && !ge.isHidden())
		{
			return ge.getBounds();
		}
		return new Rectangle(client.getCanvasWidth() / 2 - 40, 120, 80, 20);
	}

	static String formatGp(long gp)
	{
		long abs = Math.abs(gp);
		if (abs >= 1_000_000)
		{
			return String.format("%.1fM gp", gp / 1_000_000.0);
		}
		if (abs >= 1_000)
		{
			return String.format("%.1fk gp", gp / 1_000.0);
		}
		return gp + " gp";
	}

	/** Pulsierender Rahmen (M12a Dump-Alert): Alpha oszilliert ~0,9s-Periode. */
	private void drawBoxPulse(Graphics2D g, Rectangle b, Color c)
	{
		if (b == null)
		{
			return;
		}
		double t = (System.currentTimeMillis() % 900) / 900.0;
		float p = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * t)); // 0..1
		int fill = (int) (25 + 60 * p);
		int stroke = (int) (110 + 145 * p);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), fill));
		g.fillRoundRect(b.x, b.y, b.width, b.height, 6, 6);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), stroke));
		g.setStroke(new BasicStroke(2.5f));
		g.drawRoundRect(b.x, b.y, b.width, b.height, 6, 6);
	}

	private void highlightChildMaybePulse(Graphics2D g, Widget parent, int childIdx, Color color, boolean pulse)
	{
		Widget child = parent.getChild(childIdx);
		if (child != null && !child.isHidden())
		{
			if (pulse)
			{
				drawBoxPulse(g, child.getBounds(), color);
			}
			else
			{
				drawBox(g, child.getBounds(), color);
			}
		}
	}

	private void drawBox(Graphics2D g, Rectangle b, Color c)
	{
		if (b == null)
		{
			return;
		}
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 40));
		g.fillRoundRect(b.x, b.y, b.width, b.height, 6, 6);
		g.setColor(c);
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(b.x, b.y, b.width, b.height, 6, 6);
	}
}

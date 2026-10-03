package de.rsflipper;

import de.rsflipper.model.ClientSuggestion;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
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
	private static final int SETUP_QTY_ALL_BUTTON = 50; // "All" — bei Tranchen-Sells (2026-07-18)
	private static final int SETUP_QTY_BUTTON = 51;
	private static final int SETUP_PRICE_BUTTON = 54;
	private static final int SETUP_CONFIRM_BUTTON = 58;
	/** RuneLite 1.13 GE setup: displayed price value (not the price button). */
	private static final int SETUP_PRICE_VALUE = 41;
	private static final int VARBIT_SETUP_QUANTITY = 4396;
	/** Einziges zuverlaessiges Setup-Signal (Antler-guard-Fund 2026-07-18): Das
	 *  Setup-Widget 465/26 gilt AUCH in der Slot-/Offer-Ansicht als sichtbar —
	 *  nur dieses Varbit (im Setup geoeffneter Slot, 0 = keiner) trennt sauber. */
	private static final int VARBIT_SETUP_OPEN = 4439;

	private static final Color GREEN = new Color(46, 204, 113);
	private static final Color ORANGE = new Color(255, 152, 31);
	private static final Color RED = new Color(231, 76, 60);
	private static final Color TEAL = new Color(26, 188, 156);
	/** Dump-Alerts PINK statt Rot (2026-07-22): Rot bleibt exklusiv fuer Abort. */
	private static final Color DUMP_PINK = new Color(255, 64, 210);
	/** Break-even-Geduld-Plakette (2026-07-21): bewusst NICHT Orange (= Modify). */
	private static final Color AMBER = new Color(255, 196, 60);

	/** Kantenlaenge des Schloss-Knopfs am leeren Slot. */
	private static final int LOCK_SIZE = 18;
	private static final int GE_SLOTS = 8;
	/**
	 * Tooltip-Ebene der Markthalle. Beim Hovern eines Slots blendet das Spiel dort
	 * einen schwebenden Kasten neben dem Zeiger ein ("Buying: Ankou gloves 0 / 2").
	 * Er liegt NICHT im Slot-Widget - deshalb war er in den Slot-Kindern nie zu
	 * finden -, sondern in einem eigenen Container (465/33, 181x31 Pixel, Adresse
	 * per Textsuche im Widget-Baum ermittelt). Unser Overlay liegt darueber und
	 * verdeckte ihn - am deutlichsten mit dem Schloss-Knopf.
	 */
	private static final int GE_TOOLTIP_CHILD = 33;

	/**
	 * Palette des Schliessen-Knopfs der Markthalle (Sprite 535/536, Pixel fuer Pixel
	 * ausgemessen 01.09.2026). Der Knopf ist eine klassische Fase: schwarzer Rahmen,
	 * helle Kante oben/links, dunkle unten/rechts, beige Fuellung. Der Hover-Zustand
	 * dreht die Fase um — der Knopf sieht eingedrueckt aus.
	 */
	private static final Color RS_RAHMEN = new Color(0x000001);
	private static final Color RS_KANTE_HELL = new Color(0x99845F);
	private static final Color RS_ECKE_HELL = new Color(0xAA9368);
	private static final Color RS_KANTE_DUNKEL = new Color(0x3B3326);
	private static final Color RS_ECKE_DUNKEL = new Color(0x2B2B29);
	private static final Color RS_FUELLUNG_OBEN = new Color(0x8A785B);
	private static final Color RS_FUELLUNG_UNTEN = new Color(0x6A5C44);
	/** Farbe des X im Original — unser Schloss nutzt sie unveraendert. */
	private static final Color RS_SYMBOL = new Color(0x14110C);
	/** Geschlossen: dasselbe Dunkel mit Rotstich, bleibt in der Palette. */
	private static final Color RS_SYMBOL_ZU = new Color(0x4A1410);
	/** Kontur eines gesperrten Slots - dezent, der Slot ist nicht kaputt. */
	private static final Color SPERR_KONTUR = new Color(231, 76, 60, 70);
	private static final BasicStroke SPERR_STRICH = new BasicStroke(1.5f);

	private final Client client;
	private final GameStateService gameState;
	private final RSFlipperConfig config;
	private final OfferPrefill prefill;
	private final net.runelite.client.game.ItemManager itemManager;
	private volatile java.awt.image.BufferedImage coinImage; // einzelne Goldmünze (Item 995)
	/** Fertige Knopf-Bilder: offen/zu mal normal/Hover. Der Knopf sieht immer
	 *  gleich aus - einmal malen und danach nur noch kopieren spart je Bild vier
	 *  Farbverlaeufe, Strichwechsel und das Umschalten der Kantenglaettung. */
	private final java.awt.image.BufferedImage[] knopfBilder = new java.awt.image.BufferedImage[4];

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
	private final SlotLocks slotLocks;
	/** Haben WIR den Tooltip versteckt? Nur dann geben wir ihn auch wieder frei. */
	private boolean tooltipSelbstVersteckt;

	@Inject
	RSFlipperOverlay(Client client, GameStateService gameState, RSFlipperConfig config, OfferPrefill prefill,
		net.runelite.client.game.ItemManager itemManager, GeSearchSuggestion searchSuggestion,
		SlotLocks slotLocks)
	{
		this.client = client;
		this.gameState = gameState;
		this.config = config;
		this.prefill = prefill;
		this.itemManager = itemManager;
		this.searchSuggestion = searchSuggestion;
		this.slotLocks = slotLocks;
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
		// Dump-Suchzeile glatt pulsieren (2026-07-19) — per Frame wie der Slot.
		searchSuggestion.animate();
		// Hotkey-Hinweis bei offener GE-Zahleneingabe: Zeile im Look der Such-Suggestion,
		// FEST ueber der Chatbox verankert — kein Springen mehr (2026-07-19).
		int nextY = 40;
		boolean applyRowDrawn = false;
		// Do not display an old tick's suggestion after the numeric dialog was
		// accepted, changed, or closed. A typed draft belongs to the user and must
		// never be visually advertised as ready to apply.
		long pending = currentApplyValue();
		if (pending > 0)
		{
			Widget chatbox = client.getWidget(ComponentID.CHATBOX_CONTAINER);
			if (chatbox != null && !chatbox.isHidden())
			{
				drawApplyRow(g, pending, chatbox.getBounds());
				applyRowDrawn = true;
			}
		}

		// §4.6 Manuelle-Trade-Hilfe (2026-07-18): optimale Kauf-/Verkaufspreise
		// fürs offene GE-Setup — auch ohne Engine-Vorschlag (normales Spielen).
		int quoteSetupItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (!applyRowDrawn && quoteSetupItem > 0 && quoteSetupItem == prefill.getQuoteItemId()
			&& prefill.getQuoteBuy() > 0 && prefill.getQuoteSell() > 0)
		{
			// Netto-Marge dazu (Nutzerwunsch 2026-07-22): sell − GE-Steuer − buy,
			// gruen/rot — der manuelle Trader sieht sofort, ob sich der Flip lohnt.
			long qb = prefill.getQuoteBuy();
			long qs = prefill.getQuoteSell();
			long margin = qs - geTaxPerItem(qs) - qb;
			String quote = String.format("Optimal:  buy %,d  |  sell %,d  |  ", qb, qs);
			String marginTxt = String.format("%s%,d", margin >= 0 ? "+" : "-", Math.abs(margin));
			java.awt.FontMetrics qfm = g.getFontMetrics();
			g.setColor(new Color(0, 0, 0, 170));
			int qw = qfm.stringWidth(quote) + qfm.stringWidth(marginTxt) + 16;
			g.fillRoundRect(8, nextY, qw, 22, 6, 6);
			g.setColor(TEAL);
			g.drawString(quote, 16, nextY + 15);
			g.setColor(margin >= 0 ? GREEN : RED);
			g.drawString(marginTxt, 16 + qfm.stringWidth(quote), nextY + 15);
		}

		drawSlotHud(g);
		drawSlotLocks(g);
		drawProfitPopups(g);

		ClientSuggestion s = gameState.getCurrentSuggestion();
		if (s == null || !s.isActionable())
		{
			return null;
		}
		// M12a: Dump-Alerts pulsieren ROT (2026-07-19) — maximale Aufmerksamkeit.
		boolean dump = s.isDumpAlert();

		// Sell: Item im Inventar hervorheben — Klick startet das Sell-Offer.
		if ("sell".equals(s.getType()))
		{
			highlightInventoryItem(g, s.getItemId(), TEAL);
		}

		// Offer-SETUP-Screen: Menge/Preis/Bestätigen gezielt markieren (orange = ändern nötig).
		// Craw's-bow-Fund (2026-07-22): NUR wenn wirklich ein Setup offen ist
		// (Varbit 4439) — das Widget allein meldet sich auch in der Offer-Ansicht als
		// sichtbar, und die Setup-Varbits stehen dort auf 0 -> Phantom-Mengen-Highlight
		// beim Modify-Klick auf den Slot.
		Widget setup = client.getWidget(GE_GROUP_ID, SETUP_CHILD);
		int setupItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (setup != null && !setup.isHidden() && setupItem == s.getItemId()
			&& client.getVarbitValue(VARBIT_SETUP_OPEN) != 0)
		{
			int curQty = client.getVarbitValue(VARBIT_SETUP_QUANTITY);
			long curPrice = setupPrice(setup);
			// EINE Regel (2026-07-22, ersetzt die isModify-Sonderbehandlung):
			// Menge markieren NUR, wenn die Soll-Menge von der bereits im Setup
			// stehenden abweicht — uebernommene Modify-Mengen leuchten nicht mehr,
			// gewollte Mengen-Aenderungen schon (und Enter fuellt dann, s. OfferPrefill).
			boolean qtyOk = curQty == s.getQuantity();
			boolean priceKnown = curPrice > 0;
			boolean priceOk = priceKnown && curPrice == s.getPrice();
			if (!qtyOk)
			{
				// Verhalten nach §16a-Analyse (eigene Implementierung): Beim SELL
				// mit Inventarbestand == Zielmenge (Tranchen-Käufe, ungestackte Items) den
				// "All"-Button markieren — ein Klick verkauft alles, kein Zahlentippen.
				boolean sellAll = "sell".equals(s.getType())
					&& inventoryCount(s.getItemId()) == s.getQuantity();
				highlightChildMaybePulse(g, setup, sellAll ? SETUP_QTY_ALL_BUTTON : SETUP_QTY_BUTTON, dump ? DUMP_PINK : ORANGE, dump);
			}
			// An unreadable price is not evidence of a mismatch. Keep the quantity
			// guidance, but never draw a misleading price warning or confirmation.
			if (priceKnown && !priceOk)
			{
				highlightChildMaybePulse(g, setup, SETUP_PRICE_BUTTON, dump ? DUMP_PINK : ORANGE, dump);
			}
			// Only accepted values can be confirmed; an open dialog may contain a different draft.
			if (qtyOk && priceOk && client.getVarcIntValue(VarClientInt.INPUT_TYPE) == 0)
			{
				highlightChildMaybePulse(g, setup, SETUP_CONFIRM_BUTTON, dump ? DUMP_PINK : GREEN, dump);
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
				color = dump ? DUMP_PINK : TEAL;
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
				drawBoxPulse(g, slot.getBounds(), DUMP_PINK);
			}
			else
			{
				drawBox(g, slot.getBounds(), color);
			}
		}
		return null;
	}

	/** RuneLite 1.13 removed GE varbit 4398. The actual setup-price text lives
	 * at the documented setup child 41; do not search neighbouring widgets, where
	 * unrelated numbers may otherwise be mistaken for a price. */
	private long setupPrice(Widget setup)
	{
		return setup == null ? -1 : parseSetupPriceText(textOf(setup.getChild(SETUP_PRICE_VALUE)));
	}

	static long parseSetupPriceText(String text)
	{
		if (text == null)
		{
			return -1;
		}
		String visible = text.replaceAll("<[^>]*>", "").trim();
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("^([1-9][0-9]*|[1-9][0-9]{0,2}(?:,[0-9]{3})+) coins$")
			.matcher(visible);
		if (!matcher.matches())
		{
			return -1;
		}
		try
		{
			return Long.parseLong(matcher.group(1).replace(",", ""));
		}
		catch (NumberFormatException ignored)
		{
			return -1;
		}
	}

	private String textOf(Widget widget)
	{
		return widget == null ? null : widget.getText();
	}

	private long currentApplyValue()
	{
		int inputType = client.getVarcIntValue(VarClientInt.INPUT_TYPE);
		if (inputType != 7 && inputType != 30)
		{
			return 0;
		}
		String draft = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
		return draft == null || draft.trim().isEmpty() ? prefill.getCurrentInputValue() : 0;
	}

	/** Apply-Zeile bei offener Zahleneingabe (2026-07-19): exakt der Look der
	 *  Such-Suggestion — dunkler Emerald-Grund, gruene Kontur, "RS-Flipper" bold in
	 *  Markengruen, Wert weiss, "[ ENTER ]  apply" grau rechts. Fest ueber der Chatbox. */
	private void drawApplyRow(Graphics2D g, long pending, Rectangle cb)
	{
		// Unter der Eingabezeile — ungefaehr auf der Hoehe der Such-Suggestion-Zeile,
		// damit alles an derselben Stelle erscheint (2026-07-19).
		int h = 24;
		int x = cb.x + 6;
		int w = cb.width - 12;
		// OBERHALB der Frage-Zeile ("Set a price ..."), oben in der Box — gleiche
		// Hoehe wie die Item-Suggestion-Zeile der GE-Suche (2026-07-19).
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

		// Preis-Eingabe: Optimal-Quote mittig in der Zeile statt als eigene Box (2026-07-19).
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

	/** Erster freier Slot fuer einen neuen Kauf — gesperrte bleiben aussen vor
	 *  (01.09.2026: sonst leuchtete ausgerechnet der abgeschlossene Slot auf). */
	/**
	 * Blendet den schwebenden Tooltip der Markthalle aus (01.09.2026: gar kein
	 * Hover-Text, weder unserer noch der native).
	 *
	 * Aufgerufen aus BeforeRender, NICHT aus dem Overlay-Render: Overlays zeichnen
	 * erst, nachdem die Widgets gemalt sind - ein dort gesetztes setHidden kaeme
	 * immer ein Bild zu spaet, und das Spiel setzt die Ebene vor dem naechsten
	 * Zeichnen wieder sichtbar.
	 */
	void versteckeGeTooltip()
	{
		Widget tooltip = client.getWidget(GE_GROUP_ID, GE_TOOLTIP_CHILD);
		if (tooltip == null)
		{
			return;
		}
		// Haeufigster Fall, und er laeuft je Bild: Der Tooltip ist unsichtbar und wir
		// haben ihn nicht versteckt - dann gibt es nichts zu entscheiden und die
		// Slot-Suche kann entfallen.
		if (tooltip.isHidden() && !tooltipSelbstVersteckt)
		{
			return;
		}
		if (mausAufBelegtemSlot())
		{
			if (!tooltip.isHidden())
			{
				tooltip.setHidden(true);
				tooltipSelbstVersteckt = true;
			}
		}
		else if (tooltipSelbstVersteckt)
		{
			// Nur zuruecknehmen, was wir selbst gesetzt haben - danach fuehrt wieder
			// das Spiel Regie ueber die Sichtbarkeit.
			tooltip.setHidden(false);
			tooltipSelbstVersteckt = false;
		}
	}

	/**
	 * Steht der Zeiger auf einem Slot mit laufender Order? Nur dort stoert der
	 * Tooltip, denn nur dort zeichnen wir selbst etwas hin. Ueber einem leeren Slot
	 * gehoert er dem Spiel - dort erklaert er die Knoepfe zum Anlegen einer Kauf-
	 * oder Verkaufsorder (01.09.2026).
	 */
	private boolean mausAufBelegtemSlot()
	{
		java.awt.Point maus = mausPosition();
		net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (maus == null || offers == null)
		{
			return false;
		}
		for (int slot = 0; slot < GE_SLOTS && slot < offers.length; slot++)
		{
			Widget w = client.getWidget(GE_GROUP_ID, config.geSlotChildBase() + slot);
			if (w == null || w.isHidden())
			{
				continue;
			}
			Rectangle b = w.getBounds();
			if (b == null || !b.contains(maus))
			{
				continue;
			}
			net.runelite.api.GrandExchangeOffer o = offers[slot];
			return o != null && o.getState() != net.runelite.api.GrandExchangeOfferState.EMPTY;
		}
		return false;
	}

	/** Mauszeiger in Canvas-Koordinaten - dieselbe Ebene wie Widget-Rechtecke. */
	private java.awt.Point mausPosition()
	{
		net.runelite.api.Point p = client.getMouseCanvasPosition();
		return p == null ? null : new java.awt.Point(p.getX(), p.getY());
	}

	private int firstEmptySlot()
	{
		net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			java.util.Set<Integer> gesperrt = slotLocks.locked();
			for (int i = 0; i < offers.length; i++)
			{
				if (gesperrt.contains(i))
				{
					continue;
				}
				if (offers[i] == null || offers[i].getState() == net.runelite.api.GrandExchangeOfferState.EMPTY)
				{
					return i;
				}
			}
		}
		return -1;
	}

	/** Gesamtstückzahl eines Items im Inventar — Tranchen-Einzelslots UND Zertifikate
	 *  (noted, eigene ID) zählen zusammen (2026-07-18). */
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
			// normalisieren, sonst bleibt der Stack unmarkiert (2026-07-18).
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

	/** §4.6 Slot-HUD (2026-07-18): Profit-Plakette auf dem Fortschrittsbalken
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

			// Layout (2026-07-18): Alter/ETA auf der oberen Buy/Sell-Leiste;
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
				// kurz bevor); Sells: grün/rot wie gehabt (2026-07-18).
				g.setColor(info.buySide ? new Color(255, 240, 0) : (p >= 0 ? GREEN : RED)); // sattes Gelb
				g.drawString(text, x, baseline);
			}

			String age = formatClock(info.liveAgeSec());
			g.setColor(Color.BLACK);
			g.drawString(age, b.x + 6, lineY + 1);
			g.setColor(new Color(200, 200, 200));
			g.drawString(age, b.x + 5, lineY);
			// Break-even-Geduld (2026-07-21): Der Slot HAELT bewusst — ohne die
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

	/**
	 * Vorhaengeschloss am leeren GE-Slot (01.09.2026): offen = der Slot wird
	 * betreut, geschlossen = das Plugin laesst ihn in Ruhe. Nur an LEEREN Slots —
	 * liegt eine Kauf- oder Verkaufsorder drin, waere der Knopf ohne Wirkung und
	 * wuerde nur die Order-Anzeige verdecken.
	 *
	 * Der Knopf sitzt rechts oben, auf der Hoehe des "Empty"-Schriftzugs. Die
	 * Trefferflaeche meldet das Overlay an {@link SlotLocks} — der Klick wird dort
	 * ausgewertet, ohne den Client anzufassen.
	 */
	private void drawSlotLocks(Graphics2D g)
	{
		Widget geMain = client.getWidget(GE_GROUP_ID, config.geSlotChildBase());
		if (geMain == null || geMain.isHidden())
		{
			slotLocks.clearHitBoxes();
			return;
		}
		net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		java.util.Set<Integer> gesperrt = slotLocks.locked();
		java.awt.Point maus = mausPosition();

		for (int slot = 0; slot < GE_SLOTS; slot++)
		{
			Widget w = client.getWidget(GE_GROUP_ID, config.geSlotChildBase() + slot);
			if (w == null || w.isHidden())
			{
				slotLocks.clearHitBox(slot);
				continue;
			}
			boolean belegt = offers != null && slot < offers.length && offers[slot] != null
				&& offers[slot].getState() != net.runelite.api.GrandExchangeOfferState.EMPTY;
			if (belegt)
			{
				slotLocks.clearHitBox(slot);
				continue;
			}
			Rectangle b = w.getBounds();
			if (b == null || b.width <= 0)
			{
				slotLocks.clearHitBox(slot);
				continue;
			}
			boolean zu = gesperrt.contains(slot);
			Rectangle hit = new Rectangle(b.x + b.width - LOCK_SIZE - 4, b.y + 3, LOCK_SIZE, LOCK_SIZE);
			slotLocks.setHitBox(slot, hit);

			if (zu)
			{
				// Bewusst dezent: der Slot ist nicht kaputt, er ist nur ausgeklammert.
				g.setColor(SPERR_KONTUR);
				g.setStroke(SPERR_STRICH);
				g.drawRoundRect(b.x, b.y, b.width, b.height, 6, 6);
			}
			drawLockButton(g, hit, zu, maus != null && hit.contains(maus));
		}
	}

	/**
	 * Knopf im Stil des Markthallen-Schliessen-Knopfs, nur mit Schloss statt X
	 * (01.09.2026). Bewusst OHNE Kantenglaettung und aus ganzen Rechtecken
	 * gebaut: Das Original ist Pixelgrafik, ein weich gezeichnetes Symbol faellt
	 * daneben sofort auf.
	 */
	private void drawLockButton(Graphics2D g, Rectangle r, boolean geschlossen, boolean hover)
	{
		int stand = (geschlossen ? 1 : 0) | (hover ? 2 : 0);
		java.awt.image.BufferedImage img = knopfBilder[stand];
		if (img == null)
		{
			img = baueKnopf(r.width, r.height, geschlossen, hover);
			knopfBilder[stand] = img;
		}
		g.drawImage(img, r.x, r.y, null);
	}

	private java.awt.image.BufferedImage baueKnopf(int w, int h, boolean geschlossen, boolean hover)
	{
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
			w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);

		g.setColor(RS_RAHMEN);
		g.fillRect(0, 0, w, h);

		// Fuellung mit dem leichten Verlauf des Originals.
		g.setPaint(new java.awt.GradientPaint(1, 1, RS_FUELLUNG_OBEN, w - 1, h - 1, RS_FUELLUNG_UNTEN));
		g.fillRect(1, 1, w - 2, h - 2);
		g.setPaint(null);

		// Fase. Beim Hover getauscht — genau das macht Sprite 536.
		g.setColor(hover ? RS_KANTE_DUNKEL : RS_KANTE_HELL);
		g.fillRect(1, 1, w - 2, 1);
		g.fillRect(1, 1, 1, h - 2);
		g.setColor(hover ? RS_KANTE_HELL : RS_KANTE_DUNKEL);
		g.fillRect(1, h - 2, w - 2, 1);
		g.fillRect(w - 2, 1, 1, h - 2);
		g.setColor(hover ? RS_ECKE_DUNKEL : RS_ECKE_HELL);
		g.fillRect(1, 1, 1, 1);
		g.setColor(hover ? RS_ECKE_HELL : RS_ECKE_DUNKEL);
		g.fillRect(w - 2, h - 2, 1, 1);

		drawLockGlyph(g, 0, 0, w, h, geschlossen);
		g.dispose();
		return img;
	}

	/**
	 * Schloss-Symbol mittig auf den Knopf. Die Vorlagen sind schwarze Silhouetten;
	 * eingefaerbt wird ueber den Alphakanal, damit ein Bild fuer jede Farbe reicht.
	 */
	private void drawLockGlyph(Graphics2D g, int x, int y, int w, int h, boolean geschlossen)
	{
		// Laeuft nur beim Bauen der vier Knopf-Bilder, nicht je Bild - daher ohne
		// eigenen Zwischenspeicher.
		java.awt.image.BufferedImage img = einfaerben(
			net.runelite.client.util.ImageUtil.loadImageResource(RSFlipperOverlay.class,
				geschlossen ? "lock-closed.png" : "lock-open.png"),
			geschlossen ? RS_SYMBOL_ZU : RS_SYMBOL);
		g.drawImage(img, x + (w - img.getWidth()) / 2, y + (h - img.getHeight()) / 2, null);
	}

	/** Alphakanal behalten, Farbe ersetzen - so faerbt man eine Silhouette um. */
	private static java.awt.image.BufferedImage einfaerben(java.awt.image.BufferedImage src, Color c)
	{
		java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
			src.getWidth(), src.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
		int rgb = c.getRGB() & 0x00FFFFFF;
		for (int y = 0; y < src.getHeight(); y++)
		{
			for (int x = 0; x < src.getWidth(); x++)
			{
				int alpha = src.getRGB(x, y) >>> 24;
				out.setRGB(x, y, (alpha << 24) | rgb);
			}
		}
		return out;
	}

	/** GE-Steuer je Stueck (SPEC §1.4): 2 % auf den Verkaufspreis, abgerundet,
	 *  Cap 5M gp, steuerfrei unter 50 gp — identisch zur Server-Formel. */
	private static long geTaxPerItem(long sellPrice)
	{
		if (sellPrice < 50)
		{
			return 0;
		}
		return Math.min((long) Math.floor(sellPrice * 0.02), 5_000_000L);
	}

	/** Hochzählender Timer im mm:ss-Format (2026-07-18). */
	private static String formatClock(long sec)
	{
		return String.format("%d:%02d", sec / 60, sec % 60);
	}

	/** Kompakte Dauer: 45s, 12m, 1h5m. */
	static String formatDuration(long sec)
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
			g.setFont(base.deriveFont(java.awt.Font.BOLD, 25f * pop)); // 1,5x
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

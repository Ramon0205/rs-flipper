package de.rsflipper;

import de.rsflipper.model.ClientSuggestion;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontID;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * Vorgeschlagenes Item in der GE-Suche (SPEC §4.4): Öffnet der User die Kauf-Suche,
 * erscheint das vorgeschlagene Item als klickbarer Eintrag ganz oben — kein Tippen
 * nötig. Der Klick löst das spielinterne Auswahl-Script aus (der User klickt selbst,
 * §1.5). Mechanik nach Marktanalyse (§16a): der "Previous search"-Bereich
 * der Suche (Kinder 0–3 von CHATBOX_GE_SEARCH_RESULTS) wird umgeschrieben bzw. erzeugt.
 */
@Slf4j
@Singleton
public class GeSearchSuggestion
{
	private static final int SELECT_SCRIPT = 754;
	private static final int SELECT_OP_ARG = 84;
	private static final int KEY_LISTENER_ARG = -2147483640;
	private static final int BRAND_GREEN = 0x10b981;  // Marken-Emerald (Website/Logo)
	private static final int BG_DARK = 0x0e1a15;      // dunkler Emerald-Grund
	private static final int HINT_GRAY = 0x9aa5a0;
	private static final int RED = 0xe74c3c;          // Dump-Alert
	private static final int RED_BG = 0x7a2620;       // roter Zeilen-Grund im Blink-Hoch

	private final Client client;
	private final GameStateService gameState;
	private final RSFlipperConfig config;

	private int injectedItemId = -1;
	// Per-Frame-Pulsieren der Dump-Zeile (vom Overlay je Frame aufgerufen).
	private Widget pulseRect;
	private Widget pulseOutline;

	@Inject
	GeSearchSuggestion(Client client, GameStateService gameState, RSFlipperConfig config)
	{
		this.client = client;
		this.gameState = gameState;
		this.config = config;
	}

	/** Vom Plugin je GameTick aufgerufen (ClientThread). */
	public void tick()
	{
		if (config.passiveMode())
		{
			return;
		}
		Widget results = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
		if (results == null || results.isHidden())
		{
			injectedItemId = -1;
			return;
		}
		ClientSuggestion s = gameState.getCurrentSuggestion();
		if (s == null || !"buy".equals(s.getType()) || s.getItemId() <= 0)
		{
			return;
		}
		if (injectedItemId == s.getItemId())
		{
			return; // einmal injizieren; das Dump-Pulsieren laeuft per Frame ueber animate()
		}
		try
		{
			inject(results, s);
			injectedItemId = s.getItemId();
		}
		catch (Exception e)
		{
			log.debug("GE-Such-Vorschlag konnte nicht gesetzt werden", e);
		}
	}

	/** Per-Frame vom Overlay aufgerufen: pulsiert die rote Dump-Suchzeile glatt
	 *  (dieselbe Kosinus-Kurve wie der Slot). Opacity ist invertiert: 0 = deckend. */
	public void animate()
	{
		Widget rect = pulseRect;
		Widget outline = pulseOutline;
		if (rect == null || outline == null)
		{
			return;
		}
		ClientSuggestion s = gameState.getCurrentSuggestion();
		Widget results = client.getWidget(ComponentID.CHATBOX_GE_SEARCH_RESULTS);
		if (s == null || !s.isDumpAlert() || results == null || results.isHidden())
		{
			pulseRect = null;
			pulseOutline = null;
			return;
		}
		double t = (System.currentTimeMillis() % 900) / 900.0;
		float p = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * t)); // 0..1
		rect.setOpacity((int) (150 - 95 * p));    // 150 (dunkel) -> 55 (kraeftig)
		outline.setOpacity((int) (140 - 120 * p)); // 140 -> 20
		// KEIN revalidate() hier (Julian-Lag-Fund 2026-07-22): revalidate erzwingt eine
		// Layout-Neuberechnung des Chatbox-Baums — pro Frame x2 brach die Framerate ein,
		// sobald der Dump-Alert pulsierte. Opacity ist eine reine Render-Eigenschaft und
		// wird ohne revalidate uebernommen; Layout-Aenderungen macht nur inject().
	}

	private void inject(Widget results, ClientSuggestion s)
	{
		Widget[] children = results.getChildren();
		Widget rect;
		Widget label;
		Widget icon;
		Widget name;
		Widget hint;
		Widget outline;
		if (children != null && children.length >= 4
			&& children[0] != null && children[1] != null && children[2] != null && children[3] != null)
		{
			// Pfad 1: bestehenden "Previous search"-Block umschreiben.
			rect = children[0];
			label = children[1];
			name = children[2];
			icon = children[3];
		}
		else
		{
			// Pfad 2: kein Block vorhanden → selbst bauen.
			rect = results.createChild(-1, WidgetType.RECTANGLE);
			label = results.createChild(-1, WidgetType.TEXT);
			name = results.createChild(-1, WidgetType.TEXT);
			icon = results.createChild(-1, WidgetType.GRAPHIC);
		}
		// Zusatz-Kinder (Hinweis + Kontur) einmalig anlegen, danach wiederverwenden.
		children = results.getChildren();
		if (children != null && children.length >= 6 && children[4] != null && children[5] != null)
		{
			hint = children[4];
			outline = children[5];
		}
		else
		{
			hint = results.createChild(-1, WidgetType.TEXT);
			outline = results.createChild(-1, WidgetType.RECTANGLE);
		}

		int w = results.getWidth();

		// M12a Dump-Alert: rote Zeile statt Emerald-Look; die Farb-INTENSITAET pulsiert
		// per Frame ueber animate() (Ramon 2026-07-19) — genau wie der Slot im Overlay.
		boolean dump = s.isDumpAlert();
		final int bgFill = dump ? RED_BG : BG_DARK;
		int outlineColor = dump ? RED : BRAND_GREEN;
		int labelColor = dump ? RED : BRAND_GREEN;

		rect.setOriginalX(0);
		rect.setOriginalY(0);
		rect.setOriginalWidth(w);
		rect.setOriginalHeight(24);
		rect.setFilled(true);
		rect.setTextColor(bgFill);
		rect.setOpacity(dump ? 90 : 110);
		rect.setHasListener(true);
		rect.setAction(0, "Auswählen");
		rect.setOnOpListener(SELECT_SCRIPT, s.getItemId(), SELECT_OP_ARG);
		rect.setOnKeyListener(SELECT_SCRIPT, s.getItemId(), KEY_LISTENER_ARG);
		rect.setOnMouseOverListener((net.runelite.api.widgets.JavaScriptCallback) ev -> {
			rect.setTextColor(dump ? 0x9a2c24 : 0x123b2c);
			rect.setOpacity(70);
		});
		rect.setOnMouseLeaveListener((net.runelite.api.widgets.JavaScriptCallback) ev -> {
			rect.setTextColor(bgFill);
			rect.setOpacity(dump ? 90 : 110);
		});
		rect.revalidate();

		// Kontur um die Zeile (rot bei Dump, sonst Marken-Gruen).
		outline.setOriginalX(0);
		outline.setOriginalY(0);
		outline.setOriginalWidth(w);
		outline.setOriginalHeight(24);
		outline.setFilled(false);
		outline.setTextColor(outlineColor);
		outline.setOpacity(dump ? 80 : 90);
		outline.revalidate();

		// Referenzen fuers Per-Frame-Pulsieren merken (nur bei Dump aktiv).
		this.pulseRect = dump ? rect : null;
		this.pulseOutline = dump ? outline : null;

		label.setText(dump ? "DUMP" : "RS-Flipper");
		label.setFontId(FontID.BOLD_12);
		label.setTextColor(labelColor);
		label.setOriginalX(8);
		label.setOriginalY(5);
		label.setOriginalWidth(78);
		label.setOriginalHeight(15);
		label.revalidate();

		int hintW = 110;
		int hintX = w - 44 - hintW - 6;

		name.setText(s.getItemName() != null ? s.getItemName() : String.valueOf(s.getItemId()));
		name.setFontId(FontID.PLAIN_12);
		name.setTextColor(0xffffff);
		name.setOriginalX(90);
		name.setOriginalY(5);
		name.setOriginalWidth(Math.max(40, hintX - 94));
		name.setOriginalHeight(15);
		name.revalidate();

		// Hinweis: ENTER waehlt den Vorschlag direkt aus (Spiel-Listener, Ramon 2026-07-19).
		hint.setText("[ ENTER ]  select");
		hint.setFontId(FontID.PLAIN_11);
		hint.setTextColor(HINT_GRAY);
		hint.setOriginalX(hintX);
		hint.setOriginalY(6);
		hint.setOriginalWidth(hintW);
		hint.setOriginalHeight(14);
		hint.setXTextAlignment(2);
		hint.revalidate();

		icon.setItemId(s.getItemId());
		icon.setItemQuantity(1);
		icon.setItemQuantityMode(0);
		icon.setOriginalX(w - 44);
		icon.setOriginalY(0);
		icon.setOriginalWidth(36);
		icon.setOriginalHeight(24);
		icon.revalidate();

		log.debug("GE-Such-Vorschlag gesetzt: {} ({})", s.getItemName(), s.getItemId());
	}
}

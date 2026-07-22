package de.rsflipper;

import de.rsflipper.model.ClientSuggestion;
import java.awt.event.KeyEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.VarClientInt;
import net.runelite.api.VarClientStr;
import net.runelite.api.VarPlayer;
import net.runelite.api.widgets.ComponentID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;

/**
 * Hotkey-Übernahme (SPEC §4.4): Der vorgeschlagene Wert wird bei offener
 * GE-Zahleneingabe angezeigt; ein Hotkey-Druck schreibt ihn in die Eingabe —
 * 1 Tastendruck = 1 Übernahme (§1.5).
 *
 * Mechanik nach Marktanalyse (§16a): Menge-vs.-Preis wird über den
 * CHATBOX-TITEL entschieden ("How many …" = Menge, "Set a price …" = Preis) —
 * robust statt Zustandsmaschine. Der Wert wird per Widget-setText + VarcStr
 * gesetzt; KEIN Script-Aufruf (der würde die Eingabe zurücksetzen).
 */
@Slf4j
@Singleton
public class OfferPrefill implements KeyListener
{
	private static final int NUMERIC_INPUT_TYPE = 7;
	/** GE-Setup-Typ (0 = Kauf, 1 = Verkauf) — RuneLite-Varbit des Offer-Erstellbildschirms. */
	private static final int VARBIT_SETUP_TYPE = 4397;
	private static final long QUOTE_TTL_MS = 30_000;

	private final Client client;
	private final ClientThread clientThread;
	private final GameStateService gameState;
	private final RSFlipperConfig config;
	private final de.rsflipper.api.ApiClient api;

	/** §4.6 Manuelle-Trade-Hilfe: optimale Preise fürs gerade offene Setup-Item. */
	@Getter
	private volatile int quoteItemId = -1;
	@Getter
	private volatile long quoteBuy = -1;
	@Getter
	private volatile long quoteSell = -1;
	private volatile long quoteFetchedAt;
	private volatile boolean quoteInFlight;

	/** Für die Overlay-Anzeige: aktuell übernehmbarer Wert (0 = keiner). */
	@Getter
	private volatile long pendingValue;

	/** Skip-Aktion (vom Plugin gesetzt): überspringt den aktuellen Vorschlag. */
	private volatile Runnable skipHandler;

	public void setSkipHandler(Runnable handler)
	{
		this.skipHandler = handler;
	}

	@Inject
	OfferPrefill(Client client, ClientThread clientThread, GameStateService gameState, RSFlipperConfig config,
		de.rsflipper.api.ApiClient api)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.gameState = gameState;
		this.config = config;
		this.api = api;
	}

	/** Vom Plugin je GameTick aufgerufen (ClientThread): Anzeige-Wert aktuell halten. */
	public void tick()
	{
		refreshQuote();
		long v = computeValue();
		if (v <= 0)
		{
			v = computeManualQuoteValue();
		}
		pendingValue = v;
	}

	/** §4.6 (Ramon 2026-07-18): Auch bei MANUELLEN Trades den optimalen Preis liefern —
	 *  sobald ein GE-Setup offen ist, wird die Server-Quote fürs Item geholt (30s-Cache). */
	private void refreshQuote()
	{
		int setupItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (setupItem <= 0)
		{
			quoteItemId = -1;
			return;
		}
		long now = System.currentTimeMillis();
		if (setupItem == quoteItemId && now - quoteFetchedAt < QUOTE_TTL_MS)
		{
			return;
		}
		if (quoteInFlight)
		{
			return;
		}
		quoteInFlight = true;
		final int item = setupItem;
		api.getQuote(config.serverUrl(), item, q -> {
			quoteItemId = item;
			quoteBuy = q.has("buy") ? q.get("buy").getAsLong() : -1;
			quoteSell = q.has("sell") ? q.get("sell").getAsLong() : -1;
			quoteFetchedAt = System.currentTimeMillis();
			quoteInFlight = false;
		});
		// Fehlerfall: in-flight nach TTL wieder freigeben (kein Dauerblock).
		new java.util.Timer().schedule(new java.util.TimerTask()
		{
			@Override
			public void run()
			{
				quoteInFlight = false;
			}
		}, 5000);
	}

	/** Menge oder Preis — je nachdem, was die offene Eingabe gerade abfragt. */
	private long computeValue()
	{
		if (client.getVarcIntValue(VarClientInt.INPUT_TYPE) != NUMERIC_INPUT_TYPE)
		{
			return 0;
		}
		Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
		if (title == null || title.getText() == null)
		{
			return 0;
		}
		ClientSuggestion s = gameState.getCurrentSuggestion();
		int setupItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (s == null || !s.isActionable() || s.getItemId() <= 0 || setupItem != s.getItemId())
		{
			return 0;
		}
		String t = title.getText();
		// Mengen-Uebernahme gilt fuer ALLE Vorschlagstypen (Ramon 2026-07-22, ersetzt
		// die !isModify-Sperre): Wer die Mengen-Eingabe oeffnet, bekommt mit Enter die
		// Soll-Menge — das Overlay markiert das Feld ohnehin nur noch bei Abweichung.
		if (t.startsWith("How many"))
		{
			return s.getQuantity();
		}
		if (t.startsWith("Set a price"))
		{
			return s.getPrice();
		}
		return 0;
	}

	/** §4.6: Preis für MANUELLE Trades (kein passender Vorschlag) — aus der Server-Quote. */
	private long computeManualQuoteValue()
	{
		if (client.getVarcIntValue(VarClientInt.INPUT_TYPE) != NUMERIC_INPUT_TYPE)
		{
			return 0;
		}
		Widget title = client.getWidget(ComponentID.CHATBOX_TITLE);
		if (title == null || title.getText() == null || !title.getText().startsWith("Set a price"))
		{
			return 0; // Menge bleibt beim manuellen Trade Sache des Users
		}
		int setupItem = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (setupItem <= 0 || setupItem != quoteItemId)
		{
			return 0;
		}
		boolean isSell = client.getVarbitValue(VARBIT_SETUP_TYPE) == 1;
		return isSell ? quoteSell : quoteBuy;
	}

	/** Waehrend die Markthalle offen ist, darf '+' NIE in den Chat gelangen (Ramon
	 *  2026-07-19). WICHTIG: kein Widget-Zugriff hier — der Key-Handler laeuft im
	 *  AWT-Thread; der direkte getWidget-Aufruf brach den Event-Durchlauf komplett
	 *  (kein Chat-Zeichen, aber auch KEIN Skip mehr). Stattdessen Tick-Cache. */
	private boolean geOpen()
	{
		return gameState.isGeInterfaceOpen();
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (config.passiveMode())
		{
			return;
		}
		// '+' bei offener GE IMMER schlucken — unabhaengig davon, ob ein Skip
		// ausgeloest wird (sonst landet das Zeichen im Chat-Eingabefeld).
		if (e.getKeyChar() == '+' && geOpen())
		{
			e.consume();
		}
		// Layout-robust: konfigurierter Keybind ODER das Standard-Zeichen
		// (deutsche Tastaturen liefern je nach Layout unterschiedliche KeyCodes).
		// ENTER-Uebernahme (Ramon 2026-07-19): Bei OFFENER, noch LEERER Zahleneingabe
		// fuellt Enter den vorgeschlagenen Wert (2x Enter = uebernehmen + bestaetigen).
		// Hat der User selbst getippt, laeuft Enter normal durch und bestaetigt SEINEN
		// Wert — nichts wird ueberschrieben. Leeres Enter war im Spiel ein No-Op.
		String inputText = client.getVarcStrValue(VarClientStr.INPUT_TEXT);
		boolean enterKey = e.getKeyCode() == KeyEvent.VK_ENTER;
		boolean numericOpen = client.getVarcIntValue(VarClientInt.INPUT_TYPE) == NUMERIC_INPUT_TYPE;
		boolean inputEmpty = inputText == null || inputText.trim().isEmpty();
		// Nur der konfigurierte Hotkey uebernimmt (kein fest verdrahtetes '#').
		// ENTER fuellt NUR bei offener, leerer Zahleneingabe: getippte Werte bestaetigt Enter normal.
		boolean fillMatch = config.fillHotkey().matches(e)
			&& (!enterKey || (numericOpen && inputEmpty));
		boolean skipMatch = config.skipHotkey().matches(e) || e.getKeyChar() == '+';
		if (pendingValue > 0)
		{
			log.debug("Taste bei offener Eingabe: code={} char='{}' fill={} skip={}", e.getKeyCode(), e.getKeyChar(), fillMatch, skipMatch);
		}

		// Skip (§4.6): nur wenn KEINE Zahleneingabe offen ist — sonst würde '+'
		// beim Tippen eines Preises Vorschläge überspringen.
		if (skipMatch && client.getVarcIntValue(VarClientInt.INPUT_TYPE) == 0)
		{
			ClientSuggestion s = gameState.getCurrentSuggestion();
			Runnable handler = skipHandler;
			if (s != null && s.isActionable() && s.getItemId() > 0 && handler != null)
			{
				clientThread.invokeLater(handler);
				e.consume();
			}
			return;
		}

		if (!fillMatch)
		{
			return;
		}
		clientThread.invokeLater(() -> {
			long value = computeValue();
			if (value <= 0)
			{
				value = computeManualQuoteValue();
			}
			if (value <= 0)
			{
				return;
			}
			String text = String.valueOf(value);
			Widget input = client.getWidget(ComponentID.CHATBOX_FULL_INPUT);
			if (input != null)
			{
				input.setText(text + "*");
			}
			client.setVarcStrValue(VarClientStr.INPUT_TEXT, text);
			log.debug("Hotkey-Übernahme: {}", value);
		});
		e.consume();
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		// Das eigentliche Einfuegen des Zeichens laeuft ueber keyTyped — consume()
		// in keyPressed reicht NICHT, um '+' aus dem Chat fernzuhalten.
		if (!config.passiveMode() && e.getKeyChar() == '+' && geOpen())
		{
			e.consume();
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}
}

package de.rsflipper;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.MouseAdapter;

/**
 * Slot-Sperre per Vorhaengeschloss am leeren GE-Slot (01.09.2026, ersetzt das
 * CSV-Feld "Ignored GE slots" aus dem Konfigurationsdialog).
 *
 * Der Zustand liegt weiter in der Konfiguration ({@code ignoredSlots}, 1-basiert wie
 * im Spiel) — nur die Bedienung wandert an den Slot selbst. Damit bleiben bestehende
 * Einstellungen gueltig und die Sperre ueberlebt einen Client-Neustart.
 *
 * Klick-Behandlung bewusst OHNE Client-Zugriff: Die Trefferflaechen setzt das Overlay
 * pro Bild im Client-Thread, hier wird nur noch Geometrie verglichen. Ein Klick auf das
 * Schloss wird verbraucht, damit er nicht zusaetzlich das Kauf-Fenster des Slots oeffnet.
 */
@Singleton
public class SlotLocks extends MouseAdapter
{
	private final RSFlipperConfig config;
	private final ConfigManager configManager;

	/** Trefferflaeche des Schlosses je Slot (0-basiert) — pro Bild vom Overlay gepflegt. */
	private final Map<Integer, Rectangle> hitBoxes = new ConcurrentHashMap<>();

	/** Zuletzt geparster Konfigurationswert samt Ergebnis. */
	private volatile String rohStand = null;
	private volatile Set<Integer> geparst = Collections.emptySet();

	/** Wird nach einer Aenderung gerufen, damit der naechste Sync sofort geht. */
	private volatile Runnable onChanged;

	@Inject
	SlotLocks(RSFlipperConfig config, ConfigManager configManager)
	{
		this.config = config;
		this.configManager = configManager;
	}

	public void setOnChanged(Runnable r)
	{
		this.onChanged = r;
	}

	/**
	 * Gesperrte Slots, 0-basiert. Gepuffert, weil die Abfrage mehrfach je Bild
	 * laeuft (Overlay, Slot-Vorschlag, Sync) - neu geparst wird nur nach einer
	 * Aenderung.
	 */
	public Set<Integer> locked()
	{
		String roh = config.ignoredSlots();
		String schluessel = roh == null ? "" : roh;
		if (!schluessel.equals(rohStand))
		{
			geparst = parse(schluessel);
			rohStand = schluessel;
		}
		return geparst;
	}

	/** Trefferflaeche eines Schlosses melden (Overlay, je Bild). */
	public void setHitBox(int slot, Rectangle r)
	{
		hitBoxes.put(slot, r);
	}

	public void clearHitBox(int slot)
	{
		hitBoxes.remove(slot);
	}

	public void clearHitBoxes()
	{
		hitBoxes.clear();
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e)
	{
		if (!SwingUtilities.isLeftMouseButton(e))
		{
			return e;
		}
		for (Map.Entry<Integer, Rectangle> entry : hitBoxes.entrySet())
		{
			if (entry.getValue().contains(e.getPoint()))
			{
				toggle(entry.getKey());
				e.consume();
				return e;
			}
		}
		return e;
	}

	/**
	 * Auch Loslassen und Klick verbrauchen, solange der Zeiger auf dem Schloss steht.
	 * Der Client wertet beide Ereignisse aus; ohne das oeffnete der Klick trotz
	 * verbrauchtem mousePressed noch das Kauf-Fenster des Slots.
	 */
	@Override
	public MouseEvent mouseReleased(MouseEvent e)
	{
		return verbrauchenWennAufSchloss(e);
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e)
	{
		return verbrauchenWennAufSchloss(e);
	}

	private MouseEvent verbrauchenWennAufSchloss(MouseEvent e)
	{
		if (!SwingUtilities.isLeftMouseButton(e))
		{
			return e;
		}
		for (Rectangle r : hitBoxes.values())
		{
			if (r.contains(e.getPoint()))
			{
				e.consume();
				return e;
			}
		}
		return e;
	}

	private void toggle(int slot)
	{
		Set<Integer> aktuell = new LinkedHashSet<>(locked());
		if (!aktuell.remove(slot))
		{
			aktuell.add(slot);
		}
		StringBuilder sb = new StringBuilder();
		for (int s : aktuell)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(s + 1); // in der Konfiguration 1-basiert, wie die Slots im Spiel
		}
		configManager.setConfiguration(RSFlipperConfig.GROUP, "ignoredSlots", sb.toString());
		Runnable r = onChanged;
		if (r != null)
		{
			r.run();
		}
	}

	/** "7,8" -> {6,7}. Unlesbares wird still verworfen. */
	static Set<Integer> parse(String csv)
	{
		if (csv == null || csv.trim().isEmpty())
		{
			return Collections.emptySet();
		}
		Set<Integer> out = new LinkedHashSet<>();
		for (String part : csv.split(","))
		{
			try
			{
				int n = Integer.parseInt(part.trim());
				if (n >= 1 && n <= 8)
				{
					out.add(n - 1);
				}
			}
			catch (NumberFormatException ignored)
			{
				// Handeingetragener Unsinn darf die Sperre nicht kippen.
			}
		}
		return out;
	}
}

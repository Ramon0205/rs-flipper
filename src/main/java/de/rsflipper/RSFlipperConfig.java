package de.rsflipper;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(RSFlipperConfig.GROUP)
public interface RSFlipperConfig extends Config
{
	String GROUP = "rsflipper";

	enum Timeframe
	{
		M5("5 min", 5),
		M30("30 min", 30),
		H2("2 h", 120),
		H8("8 h", 480);

		private final String label;
		private final int minutes;

		Timeframe(String label, int minutes)
		{
			this.label = label;
			this.minutes = minutes;
		}

		public String label()
		{
			return label;
		}

		public int minutes()
		{
			return minutes;
		}
	}

	enum Activity
	{
		OFT("oft"),
		NORMAL("normal"),
		SELTEN("selten");

		private final String wire;

		Activity(String wire)
		{
			this.wire = wire;
		}

		public String wire()
		{
			return wire;
		}
	}

	@ConfigItem(keyName = "serverUrl", name = "Server URL", description = "Address of the RS-Flipper server", position = 0)
	default String serverUrl()
	{
		// Live-API seit 2026-07-19 (M8-Go-Live) — Dev: auf http://localhost:8080 umstellen.
		return "https://api.rs-flipper.com";
	}

	@ConfigItem(keyName = "useP2p", name = "Use P2P suggestions (free tier)", description = "Spend your 10 free members-item suggestions per day; unchecked = F2P suggestions only", hidden = true)
	default boolean useP2p()
	{
		return false;
	}

	// M12a Dump-Alerts (Ramon 2026-07-19): Schwelle in gp, 0 = Feature aus (bewusst Opt-in).
	@ConfigItem(keyName = "dumpMinProfit", name = "Dump alert min profit", description = "Minimum predicted profit for dump alerts; 0 = off", hidden = true)
	default int dumpMinProfit()
	{
		return 0;
	}


	@ConfigItem(keyName = "dumpSound", name = "Dump alert sound", description = "Play the GE offer sound when a dump alert arrives", hidden = true)
	default boolean dumpSound()
	{
		return true;
	}

	// Phase 3 (Ramon 2026-07-20): Sync an/aus ist bewusst LOKAL je Client — aus =
	// dieser Client behaelt eigene Werte (zwei Chars, zwei Setups).
	@ConfigItem(keyName = "syncSettings", name = "Sync settings across devices", description = "Keep timeframe, min profit, blocklist and dump settings in sync via your account", hidden = true)
	default boolean syncSettings()
	{
		return true;
	}

	@ConfigItem(keyName = "targetEtf", name = "Target time to fill", description = "Suggest flips with an estimated fill time within this window (5m/30m/2h/8h)", position = 2)
	default Timeframe targetEtf()
	{
		// Voreinstellung 5 min (Ramon 2026-07-19): neue User (= Free) starten mit dem
		// schnellsten Fenster — laengere Fenster laufen im Free-Tier schnell leer.
		return Timeframe.M5;
	}

	@ConfigItem(keyName = "minProfitGp", name = "Minimum profit (gp)", description = "Hard floor: only flips promising at least this profit are suggested (0 = off)", position = 3)
	default int minProfitGp()
	{
		// Voreinstellung 5k (Ramon 2026-07-19) — realistisch fuers Free-Tier.
		return 5000;
	}

	@ConfigItem(keyName = "activity", name = "Offer adjustment (legacy)", description = "Superseded by Target time to fill", position = 91)
	default Activity activity()
	{
		return Activity.NORMAL;
	}

	@ConfigItem(keyName = "blockedItems", name = "Blocked items", description = "Item IDs, comma-separated - never suggested", position = 3)
	default String blockedItems()
	{
		return "";
	}

	@ConfigItem(keyName = "highlightColor", name = "Highlight color", description = "Color of the GE slot highlight", position = 4)
	default Color highlightColor()
	{
		return new Color(255, 152, 31);
	}

	@ConfigItem(keyName = "fillHotkey", name = "Apply", description = "Key that applies the suggested value (quantity/price) to the open GE input - 1 keypress = 1 action", position = 5)
	default net.runelite.client.config.Keybind fillHotkey()
	{
		// Standard: ENTER (Ramon 2026-07-19) — 2x Enter = uebernehmen + bestaetigen.
		// '#' funktioniert zusaetzlich fest verdrahtet; umbelegbar bleibt der Keybind.
		return new net.runelite.client.config.Keybind(java.awt.event.KeyEvent.VK_ENTER, 0);
	}

	@ConfigItem(keyName = "skipHotkey", name = "Skip hotkey", description = "Key that skips the current suggestion (item paused for 10 min)", position = 6)
	default net.runelite.client.config.Keybind skipHotkey()
	{
		// Standard: + (VK_PLUS, deutsche Tastatur) — umbelegbar.
		return new net.runelite.client.config.Keybind(java.awt.event.KeyEvent.VK_PLUS, 0);
	}

	@ConfigItem(keyName = "chartHotkey", name = "Chart hotkey", description = "Key that opens the suggested item's price chart on rs-flipper.com (only while the GE is open)", position = 7)
	default net.runelite.client.config.Keybind chartHotkey()
	{
		// Standard: '-' (Ramon 2026-07-22) — wirkt nur bei offener GE, tippt dort nie in den Chat.
		return new net.runelite.client.config.Keybind(java.awt.event.KeyEvent.VK_MINUS, 0);
	}

	@ConfigItem(keyName = "passiveMode", name = "Passive mode (observe only)", description = "Plugin keeps syncing with the server (suggestion logging) but never touches the game (no highlights, hotkey fills, GE search entry or slot action swap). For running alongside other flipping plugins.", position = 8)
	default boolean passiveMode()
	{
		return false;
	}

	@ConfigItem(keyName = "slotActionSwap", name = "Slot left-click = action", description = "When abort/modify is suggested, that action becomes the default left-click on the GE slot", position = 7)
	default boolean slotActionSwap()
	{
		return true;
	}

	@ConfigItem(keyName = "geSlotChildBase", name = "GE slot widget base", description = "First slot child in the GE interface (calibration, default 7)", position = 90)
	default int geSlotChildBase()
	{
		return 7;
	}
}

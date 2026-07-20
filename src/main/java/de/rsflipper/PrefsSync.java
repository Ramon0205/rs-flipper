package de.rsflipper;

import com.google.gson.JsonObject;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Praeferenz-Sync ueber Geraete (Phase 3, Ramon 2026-07-20): Zeitfenster, Mindest-
 * Profit, Blocklist und Dump-Einstellungen wandern ueber /me/preferences mit —
 * der NEUESTE Stand gewinnt (Server-Uhr = einzige Zeit-Wahrheit; PUT liefert den
 * Stempel zurueck, Geraete-Uhren spielen keine Rolle). Die "Sync settings"-Checkbox
 * (Panel) ist bewusst LOKAL: schaltet ein User sie auf einem Client aus, behaelt
 * dieser Client eigene Werte — so fahren zwei parallele Charaktere verschiedene
 * Einstellungen (z. B. 5min/30k und 30min/15k).
 */
@Slf4j
@Singleton
public class PrefsSync
{
	/** Synchronisierte Config-Keys (Gruppe rsflipper). */
	private static final java.util.Set<String> SYNCED_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
		"targetEtf", "minProfitGp", "blockedItems", "dumpMinProfit", "dumpSound"));
	private static final String MODIFIED_KEY = "prefsModifiedAt"; // lokal, Server-Zeitstempel

	private final ConfigManager configManager;
	private final RSFlipperConfig config;
	private final de.rsflipper.api.ApiClient api;
	private final de.rsflipper.api.AuthService auth;

	/** Echo-Schutz: waehrend apply() keine Push-Trigger aus ConfigChanged. */
	private volatile boolean applying;
	private volatile boolean pushScheduled;
	private final java.util.Timer timer = new java.util.Timer("prefs-sync", true);

	@Inject
	PrefsSync(ConfigManager configManager, RSFlipperConfig config,
		de.rsflipper.api.ApiClient api, de.rsflipper.api.AuthService auth)
	{
		this.configManager = configManager;
		this.config = config;
		this.api = api;
		this.auth = auth;
	}

	private long localModifiedAt()
	{
		String v = configManager.getConfiguration(RSFlipperConfig.GROUP, MODIFIED_KEY);
		try
		{
			return v == null ? 0 : Long.parseLong(v);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private void setLocalModifiedAt(long ms)
	{
		configManager.setConfiguration(RSFlipperConfig.GROUP, MODIFIED_KEY, String.valueOf(ms));
	}

	/** Vom Plugin bei jedem ConfigChanged gerufen. */
	public void onLocalChange(String group, String key)
	{
		if (applying || !RSFlipperConfig.GROUP.equals(group) || !SYNCED_KEYS.contains(key))
		{
			return;
		}
		if (!config.syncSettings() || !auth.isLoggedIn())
		{
			return;
		}
		schedulePush();
	}

	/** Debounce (1,5 s): Mehrfach-Aenderungen buendeln, dann EIN Push. */
	private void schedulePush()
	{
		if (pushScheduled)
		{
			return;
		}
		pushScheduled = true;
		timer.schedule(new java.util.TimerTask()
		{
			@Override
			public void run()
			{
				pushScheduled = false;
				push();
			}
		}, 1500);
	}

	private void push()
	{
		if (!config.syncSettings() || !auth.isLoggedIn())
		{
			return;
		}
		JsonObject prefs = new JsonObject();
		prefs.addProperty("targetEtf", config.targetEtf().name());
		prefs.addProperty("minProfitGp", config.minProfitGp());
		prefs.addProperty("blockedItems", config.blockedItems() == null ? "" : config.blockedItems());
		prefs.addProperty("dumpMinProfit", config.dumpMinProfit());
		prefs.addProperty("dumpSound", config.dumpSound());
		api.putPreferences(config.serverUrl(), prefs, resp -> {
			if (resp.has("updatedAtMs") && !resp.get("updatedAtMs").isJsonNull())
			{
				setLocalModifiedAt(resp.get("updatedAtMs").getAsLong());
			}
			log.debug("Prefs gepusht");
		});
	}

	/** Vom Sync-Response gerufen: Server-Stand neuer als lokaler? Dann pullen. */
	public void maybePull(long serverUpdatedAtMs)
	{
		if (serverUpdatedAtMs <= 0 || !config.syncSettings() || !auth.isLoggedIn())
		{
			return;
		}
		if (serverUpdatedAtMs <= localModifiedAt())
		{
			return;
		}
		api.getPreferences(config.serverUrl(), resp -> {
			if (!resp.has("prefs") || !resp.get("prefs").isJsonObject())
			{
				return;
			}
			apply(resp.getAsJsonObject("prefs"),
				resp.has("updatedAtMs") && !resp.get("updatedAtMs").isJsonNull()
					? resp.get("updatedAtMs").getAsLong() : serverUpdatedAtMs);
		});
	}

	private void apply(JsonObject prefs, long serverMs)
	{
		applying = true;
		try
		{
			if (prefs.has("targetEtf") && !prefs.get("targetEtf").isJsonNull())
			{
				configManager.setConfiguration(RSFlipperConfig.GROUP, "targetEtf", prefs.get("targetEtf").getAsString());
			}
			if (prefs.has("minProfitGp") && !prefs.get("minProfitGp").isJsonNull())
			{
				configManager.setConfiguration(RSFlipperConfig.GROUP, "minProfitGp", prefs.get("minProfitGp").getAsInt());
			}
			if (prefs.has("blockedItems") && !prefs.get("blockedItems").isJsonNull())
			{
				configManager.setConfiguration(RSFlipperConfig.GROUP, "blockedItems", prefs.get("blockedItems").getAsString());
			}
			if (prefs.has("dumpMinProfit") && !prefs.get("dumpMinProfit").isJsonNull())
			{
				configManager.setConfiguration(RSFlipperConfig.GROUP, "dumpMinProfit", prefs.get("dumpMinProfit").getAsInt());
			}
			if (prefs.has("dumpSound") && !prefs.get("dumpSound").isJsonNull())
			{
				configManager.setConfiguration(RSFlipperConfig.GROUP, "dumpSound", prefs.get("dumpSound").getAsBoolean());
			}
			setLocalModifiedAt(serverMs);
			log.info("Prefs vom Server uebernommen (Stand {})", serverMs);
		}
		finally
		{
			applying = false;
		}
	}
}

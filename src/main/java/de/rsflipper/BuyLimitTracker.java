package de.rsflipper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Buy-Limit-Tracking (SPEC §4.2): jedes Kauf-Inkrement wird mit Zeitstempel geloggt;
 * verbraucht = Käufe der letzten 4 h (rollierendes Fenster ab erstem Kauf).
 * Persistent über Client-Neustarts (JSON pro Account-Hash).
 */
@Slf4j
public class BuyLimitTracker
{
	private static final long WINDOW_MS = 4L * 60 * 60 * 1000;
	private static final Type FILE_TYPE = new TypeToken<Map<Integer, List<long[]>>>() {}.getType();

	private final Gson gson;
	// itemId -> Liste [timestampMs, menge]
	private final Map<Integer, List<long[]>> buys = new HashMap<>();
	private long accountHash;

	public BuyLimitTracker(Gson gson)
	{
		this.gson = gson;
	}

	public synchronized void load(long accountHash)
	{
		this.accountHash = accountHash;
		buys.clear();
		File f = file();
		if (!f.exists())
		{
			return;
		}
		try
		{
			String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			Map<Integer, List<long[]>> loaded = gson.fromJson(json, FILE_TYPE);
			if (loaded != null)
			{
				buys.putAll(loaded);
			}
			prune();
			log.debug("Buy-Limits geladen: {} Items", buys.size());
		}
		catch (Exception e)
		{
			log.warn("Buy-Limit-Datei nicht lesbar — starte leer", e);
		}
	}

	public synchronized void recordBuy(int itemId, int qty, long ts)
	{
		buys.computeIfAbsent(itemId, k -> new ArrayList<>()).add(new long[]{ts, qty});
		prune();
		persist();
	}

	/** GE-Ablehnung "buy limit reached" als Korrektursignal (SPEC §12.3): Limit sofort als voll markieren. */
	public synchronized void markExhausted(int itemId, int mappingLimit, long ts)
	{
		buys.computeIfAbsent(itemId, k -> new ArrayList<>()).add(new long[]{ts, mappingLimit});
		persist();
	}

	/** Im 4h-Fenster bereits gekaufte Stückzahl. */
	public synchronized int usedInWindow(int itemId, long now)
	{
		List<long[]> list = buys.get(itemId);
		if (list == null)
		{
			return 0;
		}
		int sum = 0;
		for (long[] e : list)
		{
			if (now - e[0] < WINDOW_MS)
			{
				sum += (int) e[1];
			}
		}
		return sum;
	}

	/** Alle aktuell belasteten Items (für den AccountStatus, §12.2). */
	public synchronized Map<Integer, Integer> allUsed(long now)
	{
		prune();
		Map<Integer, Integer> out = new HashMap<>();
		for (Integer itemId : buys.keySet())
		{
			int used = usedInWindow(itemId, now);
			if (used > 0)
			{
				out.put(itemId, used);
			}
		}
		return out;
	}

	private void prune()
	{
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<Integer, List<long[]>>> it = buys.entrySet().iterator();
		while (it.hasNext())
		{
			List<long[]> list = it.next().getValue();
			list.removeIf(e -> now - e[0] >= WINDOW_MS);
			if (list.isEmpty())
			{
				it.remove();
			}
		}
	}

	private void persist()
	{
		File f = file();
		try
		{
			//noinspection ResultOfMethodCallIgnored
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), gson.toJson(buys, FILE_TYPE).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.warn("Buy-Limits konnten nicht gespeichert werden", e);
		}
	}

	private File file()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, "rs-flipper"), "buylimits_" + accountHash + ".json");
	}
}

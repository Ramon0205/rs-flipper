package de.rsflipper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.rsflipper.model.FillEvent;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Durable Outbox für Fill-Events (SPEC §4.5/§12.6): Fills werden lokal persistiert
 * und erst nach Server-Bestätigung (ackedFillIds) entfernt. Garantiert, dass bei
 * Verbindungsabbruch oder Client-Neustart kein Trade verloren geht und keiner doppelt
 * zählt (Server dedupt zusätzlich per id) — Basis für korrektes Cross-Device-Tracking.
 */
@Slf4j
public class FillOutbox
{
	private static final Type FILE_TYPE = new TypeToken<LinkedHashMap<String, FillEvent>>() {}.getType();

	private final Gson gson;
	private final Map<String, FillEvent> pending = new LinkedHashMap<>();
	private long accountHash;

	public FillOutbox(Gson gson)
	{
		this.gson = gson;
	}

	public synchronized void load(long accountHash)
	{
		this.accountHash = accountHash;
		pending.clear();
		File f = file();
		if (!f.exists())
		{
			return;
		}
		try
		{
			String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			Map<String, FillEvent> loaded = gson.fromJson(json, FILE_TYPE);
			if (loaded != null)
			{
				pending.putAll(loaded);
			}
			log.debug("Outbox geladen: {} ausstehende Fills", pending.size());
		}
		catch (Exception e)
		{
			log.warn("Outbox nicht lesbar — starte leer", e);
		}
	}

	public synchronized void add(FillEvent fill)
	{
		pending.put(fill.getId(), fill);
		persist();
	}

	public synchronized List<FillEvent> snapshot()
	{
		return new ArrayList<>(pending.values());
	}

	/** Vom Server bestätigte Fills entfernen. */
	public synchronized void ack(List<String> ids)
	{
		boolean changed = false;
		for (String id : ids)
		{
			if (pending.remove(id) != null)
			{
				changed = true;
			}
		}
		if (changed)
		{
			persist();
		}
	}

	private void persist()
	{
		File f = file();
		try
		{
			//noinspection ResultOfMethodCallIgnored
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), gson.toJson(pending, FILE_TYPE).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.warn("Outbox konnte nicht gespeichert werden", e);
		}
	}

	private File file()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, "rs-flipper"), "outbox_" + accountHash + ".json");
	}
}

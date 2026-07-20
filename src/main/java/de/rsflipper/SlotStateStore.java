package de.rsflipper;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Persistierter Zustand der 8 GE-Slots (Eigenimplementierung nach §16a-Analyse):
 * Beim Login wird der Live-Zustand gegen die Platte verglichen — so werden auch Fills erkannt,
 * die passierten, während der Client aus war (Offer füllte offline weiter). Ohne diese Persistenz
 * gingen Offline-Fills fürs Profit-Tracking verloren.
 */
@Slf4j
public class SlotStateStore
{
	@Value
	public static class SavedSlot
	{
		int itemId;
		int price;
		int totalQuantity;
		int quantityTraded;
		long spent;
		String state;
		boolean suggestedPriceUsed;
	}

	private static final Type FILE_TYPE = new TypeToken<HashMap<Integer, SavedSlot>>() {}.getType();

	private final Gson gson;
	private final Map<Integer, SavedSlot> slots = new HashMap<>();
	private long accountHash;

	public SlotStateStore(Gson gson)
	{
		this.gson = gson;
	}

	public synchronized void load(long accountHash)
	{
		this.accountHash = accountHash;
		slots.clear();
		File f = file();
		if (!f.exists())
		{
			return;
		}
		try
		{
			String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			Map<Integer, SavedSlot> loaded = gson.fromJson(json, FILE_TYPE);
			if (loaded != null)
			{
				slots.putAll(loaded);
			}
			log.debug("Slot-Zustände geladen: {}", slots.size());
		}
		catch (Exception e)
		{
			log.warn("Slot-Zustandsdatei nicht lesbar — starte leer", e);
		}
	}

	public synchronized SavedSlot get(int slot)
	{
		return slots.get(slot);
	}

	public synchronized void put(int slot, SavedSlot state)
	{
		if (state == null)
		{
			slots.remove(slot);
		}
		else
		{
			slots.put(slot, state);
		}
		persist();
	}

	private void persist()
	{
		File f = file();
		try
		{
			//noinspection ResultOfMethodCallIgnored
			f.getParentFile().mkdirs();
			Files.write(f.toPath(), gson.toJson(slots, FILE_TYPE).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.warn("Slot-Zustände konnten nicht gespeichert werden", e);
		}
	}

	private File file()
	{
		return new File(new File(RuneLite.RUNELITE_DIR, "rs-flipper"), "slots_" + accountHash + ".json");
	}
}

package de.rsflipper.model;

import com.google.gson.JsonObject;
import lombok.Value;

/** Server-Suggestion aus Client-Sicht (Vertrag SPEC §5). */
@Value
public class ClientSuggestion
{
	String suggestionId;
	String type;      // buy | sell | collect | abort | modify_buy | modify_sell | wait
	int boxId;
	int itemId;
	String itemName;
	long price;
	int quantity;
	String message;
	String badge;
	/** Erwarteter Gesamt-Profit des Flips (konservative Marge × Menge), −1 = unbekannt. */
	long expectedProfit;
	/** Geschätzte Dauer bis Flip-Abschluss in Minuten (Kauf + Verkauf), −1 = unbekannt. */
	double fillTimeMin;
	/** §4.6 Mini-Preisgraph: 5m-Serie (high/low), leere Arrays = kein Graph. */
	long[] graphTs;
	long[] graphHigh;
	long[] graphLow;
	/** Score-Erklärung (§5 "why") als Rohobjekt für die Warum-Anzeige (null = keine). */
	JsonObject why;
	/** Teil eines Modify-Flows (Pin): Anzeige bleibt 'Change Order' in Orange (Ramon 2026-07-18). */
	boolean modifyFlow;
	/** Ziel-Fill-Zeit wurde gelockert (kein Item im Zeitfenster) — kleiner Hinweis in der Box. */
	boolean zeitGelockert;
	/** Realisierter Profit der fertigen Sell-Order beim Collect (Ramon 2026-07-19); Long.MIN_VALUE = unbekannt. */
	long collectProfit;

	public static ClientSuggestion from(JsonObject o)
	{
		long expectedProfit = Long.MIN_VALUE; // Sentinel: unbekannt (-1 gp wäre ein gültiger Verlust)
		double fillTimeMin = -1;
		JsonObject whyObj = o.has("why") && o.get("why").isJsonObject() ? o.getAsJsonObject("why") : null;
		// §4.6: Server liefert für Sell/Modify den potenziellen Profit direkt (kann negativ sein).
		if (whyObj != null && whyObj.has("potenziellerProfit") && !whyObj.get("potenziellerProfit").isJsonNull())
		{
			expectedProfit = whyObj.get("potenziellerProfit").getAsLong();
		}
		long[] gTs = new long[0];
		long[] gHigh = new long[0];
		long[] gLow = new long[0];
		if (o.has("graphData") && o.get("graphData").isJsonArray())
		{
			com.google.gson.JsonArray arr = o.getAsJsonArray("graphData");
			gTs = new long[arr.size()];
			gHigh = new long[arr.size()];
			gLow = new long[arr.size()];
			for (int i = 0; i < arr.size(); i++)
			{
				JsonObject pnt = arr.get(i).getAsJsonObject();
				gTs[i] = pnt.get("ts").getAsLong();
				gHigh[i] = pnt.get("high").isJsonNull() ? -1 : pnt.get("high").getAsLong();
				gLow[i] = pnt.get("low").isJsonNull() ? -1 : pnt.get("low").getAsLong();
			}
		}
		if (o.has("why") && o.get("why").isJsonObject())
		{
			JsonObject why = o.getAsJsonObject("why");
			if (why.has("margeKonservativ") && o.has("quantity"))
			{
				expectedProfit = why.get("margeKonservativ").getAsLong() * o.get("quantity").getAsLong();
			}
			if (why.has("fillZeitMin"))
			{
				fillTimeMin = why.get("fillZeitMin").getAsDouble();
			}
		}
		return new ClientSuggestion(
			str(o, "suggestionId"),
			str(o, "type"),
			o.has("boxId") ? o.get("boxId").getAsInt() : -1,
			o.has("itemId") ? o.get("itemId").getAsInt() : -1,
			str(o, "itemName"),
			o.has("price") && !o.get("price").isJsonNull() ? o.get("price").getAsLong() : 0,
			o.has("quantity") && !o.get("quantity").isJsonNull() ? o.get("quantity").getAsInt() : 0,
			str(o, "message"),
			str(o, "badge"),
			expectedProfit,
			fillTimeMin,
			gTs,
			gHigh,
			gLow,
			whyObj,
			whyObj != null && whyObj.has("modifyFlow") && !whyObj.get("modifyFlow").isJsonNull()
				&& whyObj.get("modifyFlow").getAsBoolean(),
			whyObj != null && whyObj.has("zeitGelockert") && !whyObj.get("zeitGelockert").isJsonNull()
				&& whyObj.get("zeitGelockert").getAsBoolean(),
			whyObj != null && whyObj.has("collectProfit") && !whyObj.get("collectProfit").isJsonNull()
				? whyObj.get("collectProfit").getAsLong() : Long.MIN_VALUE
		);
	}

	private static String str(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	public boolean isActionable()
	{
		return type != null && !"wait".equals(type);
	}

	/** M12a: markierter Dump-Alert (why.dumpAlert = true) — kein ETA, instant kaufen. */
	public boolean isDumpAlert()
	{
		return why != null && why.has("dumpAlert")
			&& !why.get("dumpAlert").isJsonNull() && why.get("dumpAlert").getAsBoolean();
	}
}

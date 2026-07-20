package de.rsflipper.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP-Schicht zum RS-Flipper-Server (SPEC §11): AccountStatus hin, Suggestion zurück.
 * Async über OkHttp — blockiert nie den ClientThread. Auth (JWT) folgt in M9.
 */
@Slf4j
@Singleton
public class ApiClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final OkHttpClient http;
	private final Gson gson;
	private final AuthService auth;

	@Inject
	public ApiClient(OkHttpClient http, Gson gson, AuthService auth)
	{
		this.http = http;
		this.gson = gson;
		this.auth = auth;
	}

	/** M9: Authorization-Header anhaengen, wenn eingeloggt. */
	private Request.Builder authed(Request.Builder b)
	{
		String bearer = auth.bearer();
		if (bearer != null)
		{
			b.header("Authorization", bearer);
		}
		return b;
	}

	/** §4.6: optimale Order-Preise für manuelle Trades (Quote-Hilfe). */
	public void getQuote(String serverUrl, int itemId, Consumer<JsonObject> onSuccess)
	{
		Request request = authed(new Request.Builder())
			.url(serverUrl + "/prices/quote/" + itemId)
			.get()
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						onSuccess.accept(gson.fromJson(r.body().string(), JsonObject.class));
					}
				}
				catch (Exception ignored)
				{
				}
			}
		});
	}

	/** Phase 3: Praeferenzen lesen (Sync ueber Geraete). */
	public void getPreferences(String serverUrl, Consumer<JsonObject> onSuccess)
	{
		Request request = authed(new Request.Builder())
			.url(serverUrl + "/me/preferences")
			.get()
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						onSuccess.accept(gson.fromJson(r.body().string(), JsonObject.class));
					}
				}
				catch (Exception ignored)
				{
				}
			}
		});
	}

	/** Phase 3: Praeferenzen schreiben — Antwort enthaelt updatedAtMs (Server-Uhr). */
	public void putPreferences(String serverUrl, JsonObject prefs, Consumer<JsonObject> onSuccess)
	{
		JsonObject body = new JsonObject();
		body.add("prefs", prefs);
		Request request = authed(new Request.Builder())
			.url(serverUrl + "/me/preferences")
			.put(RequestBody.create(MediaType.parse("application/json"), body.toString()))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						onSuccess.accept(gson.fromJson(r.body().string(), JsonObject.class));
					}
				}
				catch (Exception ignored)
				{
				}
			}
		});
	}

	/** Phase 4: Feedback/Bug-Report an den Server. */
	public void postFeedback(String serverUrl, String category, String message, JsonObject context,
		Consumer<Boolean> onDone)
	{
		JsonObject body = new JsonObject();
		body.addProperty("category", category);
		body.addProperty("message", message);
		body.add("context", context);
		Request request = authed(new Request.Builder())
			.url(serverUrl + "/feedback")
			.post(RequestBody.create(JSON, body.toString()))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onDone.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					onDone.accept(r.isSuccessful());
				}
			}
		});
	}

	/** M12a: leichter Dump-Alert-Poll (schnelle Zustellung, ohne den grossen Sync). */
	public void getDumpAlert(String serverUrl, int minProfit, long gp, Consumer<JsonObject> onSuccess)
	{
		Request request = authed(new Request.Builder())
			.url(serverUrl + "/dump/poll?minProfit=" + minProfit + "&gp=" + gp)
			.get()
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						onSuccess.accept(gson.fromJson(r.body().string(), JsonObject.class));
					}
				}
				catch (Exception ignored)
				{
				}
			}
		});
	}

	/** §4.5 Stats-Tab: aggregierte Flip-Statistik vom Server (sinceTs = Session-Beginn). */
	public void getStats(String serverUrl, long accountHash, long sinceTs, long itemsSince, long dayStart, Consumer<JsonObject> onSuccess, Consumer<String> onError)
	{
		Request request = authed(new Request.Builder())
			.url(serverUrl + "/me/stats?accountHash=" + accountHash + "&sinceTs=" + sinceTs + "&itemsSince=" + itemsSince + "&dayStart=" + dayStart)
			.get()
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onError.accept("Server nicht erreichbar");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onError.accept("Stats-Fehler " + r.code());
						return;
					}
					onSuccess.accept(gson.fromJson(r.body().string(), JsonObject.class));
				}
				catch (Exception e)
				{
					onError.accept("Stats unlesbar");
				}
			}
		});
	}

	public void postSuggestion(String serverUrl, JsonObject accountStatus, Consumer<JsonObject> onSuccess, Consumer<String> onError)
	{
		Request request = authed(new Request.Builder())
			.url(serverUrl + "/suggestion")
			.post(RequestBody.create(JSON, accountStatus.toString()))
			.build();

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Server nicht erreichbar: {}", e.getMessage());
				onError.accept("Server nicht erreichbar");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.code() == 401)
					{
						onError.accept("AUTH_401"); // M9: Sync stoesst Token-Refresh an
						return;
					}
					if (!r.isSuccessful() || r.body() == null)
					{
						onError.accept("Server-Fehler HTTP " + r.code());
						return;
					}
					JsonObject body = gson.fromJson(r.body().string(), JsonObject.class);
					onSuccess.accept(body);
				}
				catch (Exception e)
				{
					log.warn("Antwort nicht lesbar", e);
					onError.accept("Antwort nicht lesbar");
				}
			}
		});
	}
}

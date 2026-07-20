package de.rsflipper.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * M9 (SPEC §12.1): Login gegen die RS-Flipper-API. Access-Token (1h) + Refresh-Token
 * (30 Tage) werden über den ConfigManager persistiert (überleben Client-Neustarts,
 * versteckte Keys ohne @ConfigItem). Bei 401 versucht der Plugin-Sync einen Refresh.
 */
@Slf4j
@Singleton
public class AuthService
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String GROUP = "rsflipper";
	private static final String KEY_ACCESS = "authAccessToken";
	private static final String KEY_REFRESH = "authRefreshToken";
	private static final String KEY_EMAIL = "authEmail";

	private final OkHttpClient http;
	private final Gson gson;
	private final ConfigManager configManager;
	private volatile boolean refreshing = false;

	@Inject
	public AuthService(OkHttpClient http, Gson gson, ConfigManager configManager)
	{
		this.http = http;
		this.gson = gson;
		this.configManager = configManager;
	}

	public boolean isLoggedIn()
	{
		return accessToken() != null;
	}

	public String email()
	{
		return configManager.getConfiguration(GROUP, KEY_EMAIL);
	}

	public String accessToken()
	{
		String t = configManager.getConfiguration(GROUP, KEY_ACCESS);
		return t == null || t.isEmpty() ? null : t;
	}

	private String refreshToken()
	{
		String t = configManager.getConfiguration(GROUP, KEY_REFRESH);
		return t == null || t.isEmpty() ? null : t;
	}

	/** Authorization-Header-Wert oder null (Dev ohne Login). */
	public String bearer()
	{
		String t = accessToken();
		return t == null ? null : "Bearer " + t;
	}

	public void logout()
	{
		configManager.unsetConfiguration(GROUP, KEY_ACCESS);
		configManager.unsetConfiguration(GROUP, KEY_REFRESH);
		configManager.unsetConfiguration(GROUP, KEY_EMAIL);
	}

	public void login(String serverUrl, String email, String password, Consumer<String> onDone)
	{
		JsonObject body = new JsonObject();
		body.addProperty("email", email);
		body.addProperty("password", password);
		Request request = new Request.Builder()
			.url(serverUrl + "/auth/login")
			.post(RequestBody.create(JSON, body.toString()))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onDone.accept("Server unreachable");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					JsonObject d = gson.fromJson(r.body() != null ? r.body().string() : "{}", JsonObject.class);
					if (!r.isSuccessful())
					{
						onDone.accept(d != null && d.has("error") ? d.get("error").getAsString() : "Login failed (" + r.code() + ")");
						return;
					}
					configManager.setConfiguration(GROUP, KEY_ACCESS, d.get("accessToken").getAsString());
					configManager.setConfiguration(GROUP, KEY_REFRESH, d.get("refreshToken").getAsString());
					configManager.setConfiguration(GROUP, KEY_EMAIL, email);
					onDone.accept(null); // null = Erfolg
				}
				catch (Exception e)
				{
					onDone.accept("Login response unreadable");
				}
			}
		});
	}

	/** Phase 6: Login per Discord (Device-Flow). Oeffnet den Browser mit einem
	 *  Einmal-Code; der Server hinterlegt die Tokens nach dem OAuth-Callback und
	 *  wir pollen sie hier ab (2,5s-Takt, max. 4 Minuten). */
	public void loginWithDiscord(String serverUrl, Consumer<String> onDone)
	{
		byte[] rnd = new byte[16];
		new java.security.SecureRandom().nextBytes(rnd);
		StringBuilder sb = new StringBuilder();
		for (byte b : rnd)
		{
			sb.append(String.format("%02x", b));
		}
		String code = sb.toString();
		net.runelite.client.util.LinkBrowser.browse(serverUrl + "/auth/discord/start?device=" + code);
		Thread poller = new Thread(() -> {
			try
			{
				for (int i = 0; i < 96; i++)
				{
					Thread.sleep(2500);
					JsonObject body = new JsonObject();
					body.addProperty("code", code);
					Request request = new Request.Builder()
						.url(serverUrl + "/auth/device/poll")
						.post(RequestBody.create(JSON, body.toString()))
						.build();
					try (Response r = http.newCall(request).execute())
					{
						if (!r.isSuccessful() || r.body() == null)
						{
							continue;
						}
						JsonObject d = gson.fromJson(r.body().string(), JsonObject.class);
						if (d != null && d.has("ready") && d.get("ready").getAsBoolean())
						{
							configManager.setConfiguration(GROUP, KEY_ACCESS, d.get("accessToken").getAsString());
							configManager.setConfiguration(GROUP, KEY_REFRESH, d.get("refreshToken").getAsString());
							configManager.setConfiguration(GROUP, KEY_EMAIL, d.get("email").getAsString());
							onDone.accept(null);
							return;
						}
					}
					catch (IOException ignored)
					{
						// Netz-Huckel: einfach weiterpollen
					}
				}
				onDone.accept("Discord login timed out - please try again");
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		}, "rsf-discord-login");
		poller.setDaemon(true);
		poller.start();
	}

	/** Access-Token per Refresh-Token erneuern (max. 1 Versuch parallel). */
	public void tryRefresh(String serverUrl)
	{
		String refresh = refreshToken();
		if (refresh == null || refreshing)
		{
			return;
		}
		refreshing = true;
		JsonObject body = new JsonObject();
		body.addProperty("refreshToken", refresh);
		Request request = new Request.Builder()
			.url(serverUrl + "/auth/refresh")
			.post(RequestBody.create(JSON, body.toString()))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				refreshing = false;
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						JsonObject d = gson.fromJson(r.body().string(), JsonObject.class);
						configManager.setConfiguration(GROUP, KEY_ACCESS, d.get("accessToken").getAsString());
						log.debug("Access-Token erneuert");
					}
					else if (r.code() == 401)
					{
						// Refresh-Token abgelaufen/widerrufen -> sauber ausloggen.
						logout();
					}
				}
				catch (Exception ignored)
				{
				}
				finally
				{
					refreshing = false;
				}
			}
		});
	}
}

package dev.binwatch.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.HttpTransport;
import dev.binwatch.log.BwLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps the bot connected to Discord's gateway while the game runs: Discord only lets a bot post after it has
 * connected, the bot shows as online, and slash commands ({@code /profit}, {@code /status}) arrive here. No privileged
 * intents are requested, so the bot cannot read anyone's messages. Answers are ephemeral (only the person who ran the
 * command sees them), and the commands are limited to server administrators by default.
 */
public final class DiscordGateway {
	static final URI GATEWAY = URI.create("wss://gateway.discord.gg/?v=10&encoding=json");
	private static final long MAX_BACKOFF_MS = 120_000;

	private final DiscordBot bot;
	private final Supplier<String> token;
	private final Supplier<Boolean> slashCommands;
	/** Command name → response embed payload (with "embeds"); null for unknown commands. */
	private final Function<String, JsonObject> commands;
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
	private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "BINWatch-discord-heartbeat"));
	private final ExecutorService handlers = Executors.newSingleThreadExecutor(r -> daemon(r, "BINWatch-discord-commands"));
	private volatile boolean running;
	private volatile String status = "not started";
	private Thread loop;

	public DiscordGateway(DiscordBot bot, Supplier<String> token, Supplier<Boolean> slashCommands,
			Function<String, JsonObject> commands) {
		this.bot = bot;
		this.token = token;
		this.slashCommands = slashCommands;
		this.commands = commands;
	}

	private static Thread daemon(Runnable r, String name) {
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}

	public String status() {
		return status;
	}

	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		loop = daemon(this::run, "BINWatch-discord-gateway");
		loop.start();
	}

	public synchronized void stop() {
		running = false;
		if (loop != null) {
			loop.interrupt();
		}
		heartbeat.shutdownNow();
		handlers.shutdownNow();
		status = "stopped";
	}

	private void run() {
		long backoff = 5_000;
		while (running) {
			String t = token.get();
			if (t == null || t.isBlank()) {
				status = "no bot token";
				sleep(10_000);
				continue;
			}
			Session s = new Session(t.trim());
			int code = s.runUntilClosed();
			if (!running) {
				return;
			}
			if (code == 4004) {
				status = "token rejected by Discord (4004); fix discordBotToken in secrets.json";
				BwLog.warn("Discord bot token was rejected by Discord; the bot stays offline until secrets.json is fixed");
				String rejected = t;
				while (running && rejected.equals(token.get())) {
					sleep(10_000);
				}
				continue;
			}
			if (s.ready) {
				backoff = 5_000;
			}
			status = "reconnecting in " + backoff / 1000 + " s (last close " + code + ")";
			sleep(backoff);
			backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** One gateway connection, from connect to close. */
	private final class Session implements WebSocket.Listener {
		private final String token;
		private final CountDownLatch closed = new CountDownLatch(1);
		private final StringBuilder buffer = new StringBuilder();
		private volatile WebSocket ws;
		private volatile Long seq;
		private volatile boolean acked = true;
		private volatile boolean ready;
		private volatile int closeCode = 1000;
		private ScheduledFuture<?> beat;

		Session(String token) {
			this.token = token;
		}

		int runUntilClosed() {
			status = "connecting";
			try {
				ws = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(GATEWAY, this).join();
				closed.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException e) {
				BwLog.throttled(BwLog.Level.WARN, "discord-gateway", "Discord gateway connection failed: "
						+ e.getClass().getSimpleName(), null);
				closeCode = -1;
			} finally {
				if (beat != null) {
					beat.cancel(false);
				}
				WebSocket w = ws;
				if (w != null && !w.isOutputClosed()) {
					w.abort();
				}
			}
			return closeCode;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			// Discord's hello can arrive before buildAsync() returns, so the socket is taken from here.
			ws = webSocket;
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				String msg = buffer.toString();
				buffer.setLength(0);
				try {
					handle(JsonParser.parseString(msg).getAsJsonObject());
				} catch (RuntimeException e) {
					BwLog.throttled(BwLog.Level.WARN, "discord-gateway-msg", "Discord gateway message not understood: "
							+ e.getClass().getSimpleName(), null);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			closeCode = statusCode;
			closed.countDown();
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			closeCode = -1;
			BwLog.throttled(BwLog.Level.WARN, "discord-gateway", "Discord gateway error: " + error.getClass().getSimpleName(), null);
			closed.countDown();
		}

		private void handle(JsonObject m) {
			int op = m.get("op").getAsInt();
			if (m.has("s") && !m.get("s").isJsonNull()) {
				seq = m.get("s").getAsLong();
			}
			switch (op) {
				case 10 -> { // hello
					long interval = m.getAsJsonObject("d").get("heartbeat_interval").getAsLong();
					beat = heartbeat.scheduleAtFixedRate(this::beat, (long) (interval * Math.random()), interval,
							TimeUnit.MILLISECONDS);
					identify();
				}
				case 11 -> acked = true;
				case 1 -> send(heartbeatPayload());
				case 7, 9 -> ws.sendClose(4000, "reconnect"); // reconnect / invalid session: start a fresh session
				case 0 -> dispatch(m.get("t").getAsString(), m.getAsJsonObject("d"));
				default -> {
				}
			}
		}

		private void beat() {
			if (!acked) {
				ws.sendClose(4000, "heartbeat not acknowledged");
				return;
			}
			acked = false;
			send(heartbeatPayload());
		}

		private String heartbeatPayload() {
			JsonObject o = new JsonObject();
			o.addProperty("op", 1);
			if (seq == null) {
				o.add("d", null);
			} else {
				o.addProperty("d", seq);
			}
			return o.toString();
		}

		private void identify() {
			JsonObject props = new JsonObject();
			props.addProperty("os", System.getProperty("os.name", "unknown"));
			props.addProperty("browser", "binwatch");
			props.addProperty("device", "binwatch");
			JsonObject activity = new JsonObject();
			activity.addProperty("name", "the Auction House");
			activity.addProperty("type", 3);
			JsonArray activities = new JsonArray();
			activities.add(activity);
			JsonObject presence = new JsonObject();
			presence.add("activities", activities);
			presence.addProperty("status", "online");
			presence.addProperty("afk", false);
			presence.add("since", null);
			JsonObject d = new JsonObject();
			d.addProperty("token", token);
			d.addProperty("intents", 0);
			d.add("properties", props);
			d.add("presence", presence);
			JsonObject o = new JsonObject();
			o.addProperty("op", 2);
			o.add("d", d);
			send(o.toString());
		}

		private synchronized void send(String text) {
			try {
				ws.sendText(text, true).join();
			} catch (RuntimeException e) {
				BwLog.throttled(BwLog.Level.WARN, "discord-gateway-send", "Discord gateway send failed: "
						+ e.getClass().getSimpleName(), null);
			}
		}

		private void dispatch(String type, JsonObject d) {
			if ("READY".equals(type)) {
				ready = true;
				String name = d.getAsJsonObject("user").get("username").getAsString();
				String appId = d.getAsJsonObject("application").get("id").getAsString();
				status = "online as " + name;
				BwLog.info("Discord bot online as " + name);
				handlers.execute(() -> registerCommands(appId));
			} else if ("INTERACTION_CREATE".equals(type)) {
				handlers.execute(() -> answer(d));
			}
		}
	}

	/** Registers (or, with slash commands off, removes) the bot's global commands. Idempotent. */
	private void registerCommands(String appId) {
		JsonArray list = new JsonArray();
		if (Boolean.TRUE.equals(slashCommands.get())) {
			list.add(command("profit", "BIN Watch profit: last hour, 24 h, overall and top flips"));
			list.add(command("status", "BIN Watch status: API, market checks, Discord"));
		}
		try {
			HttpTransport.Response r = bot.request("PUT", "/applications/" + appId + "/commands", list.toString(),
					Duration.ofSeconds(15));
			if (r.status() >= 200 && r.status() < 300) {
				BwLog.info("Discord slash commands " + (list.isEmpty() ? "removed" : "registered: /profit, /status"));
			} else {
				BwLog.throttled(BwLog.Level.WARN, "discord-commands", "Discord slash commands not registered: HTTP " + r.status(), null);
			}
		} catch (Exception e) {
			BwLog.throttled(BwLog.Level.WARN, "discord-commands", "Discord slash commands not registered: "
					+ e.getClass().getSimpleName(), null);
		}
	}

	static JsonObject command(String name, String description) {
		JsonObject c = new JsonObject();
		c.addProperty("name", name);
		c.addProperty("description", description);
		c.addProperty("type", 1);
		c.addProperty("default_member_permissions", "0"); // administrators only, unless changed in server settings
		return c;
	}

	private void answer(JsonObject interaction) {
		try {
			if (interaction.get("type").getAsInt() != 2) {
				return;
			}
			String id = interaction.get("id").getAsString();
			String itoken = interaction.get("token").getAsString();
			String name = interaction.getAsJsonObject("data").get("name").getAsString();
			JsonObject reply = commands.apply(name);
			JsonObject data = new JsonObject();
			if (reply == null) {
				data.addProperty("content", "Unknown command.");
			} else {
				JsonElement embeds = reply.get("embeds");
				data.add("embeds", embeds);
			}
			data.addProperty("flags", 64);
			JsonObject allowed = new JsonObject();
			allowed.add("parse", new JsonArray());
			data.add("allowed_mentions", allowed);
			JsonObject body = new JsonObject();
			body.addProperty("type", 4);
			body.add("data", data);
			if (!id.matches("\\d{1,30}") || !itoken.matches("[A-Za-z0-9_\\-.]{10,500}")) {
				return;
			}
			HttpTransport.Response r = bot.request("POST", "/interactions/" + id + "/" + itoken + "/callback", body.toString(),
					Duration.ofSeconds(3));
			BwLog.info("Discord /" + name + " answered (HTTP " + r.status() + ")");
		} catch (Exception e) {
			BwLog.throttled(BwLog.Level.WARN, "discord-interaction", "Discord command not answered: " + e.getClass().getSimpleName(), null);
		}
	}

}

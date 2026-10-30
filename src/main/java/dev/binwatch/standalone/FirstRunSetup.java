package dev.binwatch.standalone;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.binwatch.api.HttpTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.config.ConfigManager;
import dev.binwatch.config.Secrets;
import dev.binwatch.store.AtomicFiles;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * First-run configuration on the console: asks for the Discord webhook, checks it with Discord (a GET of the webhook,
 * which posts nothing), saves it to {@code secrets.json} readable only by the current user, and offers the main
 * thresholds. The Hypixel auction endpoint needs no API key, so none is asked for. The webhook is never echoed back.
 */
final class FirstRunSetup {
	/** Reads one answer; {@code secret} answers are not echoed when a real console is attached. Null at end of input. */
	interface Input {
		String read(String prompt, boolean secret);
	}

	record WebhookCheck(boolean ok, boolean definitive, String message) {
	}

	private final Input in;
	private final ConsoleOut out;
	private final Supplier<HttpTransport> http;
	private final ConfigManager files;

	FirstRunSetup(Input in, ConsoleOut out, Supplier<HttpTransport> http, ConfigManager files) {
		this.in = in;
		this.out = out;
		this.http = http;
		this.files = files;
	}

	/** Standalone defaults: the board replaces per-flip alerts, and the in-game features have nothing to do here. */
	static BinWatchConfig standaloneDefaults() {
		BinWatchConfig c = new BinWatchConfig();
		c.discord.enabled = false;
		c.summary.enabled = false;
		c.discordBot.enabled = false;
		c.chat.alerts = false;
		c.autoView.enabled = false;
		return c;
	}

	/**
	 * Makes sure a webhook is configured, asking for one if needed (or always, with {@code force}). Returns false when
	 * there is none and none could be obtained.
	 */
	boolean ensureWebhook(boolean force) throws IOException {
		Secrets secrets = loadSecrets();
		if (secrets.hasWebhook() && !force) {
			return true;
		}
		out.line("CONFIG", "Discord webhook for the AH Flipper board: Discord channel → Edit Channel → Integrations → "
				+ "Webhooks → New Webhook → Copy Webhook URL.");
		String url = askWebhook("the AH Flipper board", secrets.hasWebhook(), false);
		if (url == null) {
			return secrets.hasWebhook();
		}
		secrets.discordWebhookUrl = url;
		saveSecrets(secrets);
		out.line("CONFIG", "Webhook saved to " + files.secretsFile() + " (readable only by your user).");
		return true;
	}

	/** Asks for the optional profit tracker webhook; Enter skips it or keeps the current one. */
	void askProfitWebhook() throws IOException {
		Secrets secrets = loadSecrets();
		out.line("CONFIG", "Optional: a second webhook, in another channel, for the profit tracker (flips that were "
				+ "still unsold when alerted, and how long they stayed buyable).");
		String url = askWebhook("the profit tracker", secrets.hasProfitWebhook(), true);
		if (url == null) {
			out.line("CONFIG", secrets.hasProfitWebhook() ? "Profit tracker webhook unchanged."
					: "No profit tracker webhook: its results are only shown in the console (add one with --setup).");
			return;
		}
		secrets.discordProfitWebhookUrl = url;
		saveSecrets(secrets);
		out.line("CONFIG", "Profit tracker webhook saved.");
	}

	private Secrets loadSecrets() {
		ConfigManager.LoadResult<Secrets> loaded = files.loadSecrets();
		restrictToOwner(files.secretsFile());
		if (!loaded.ok()) {
			loaded.errors().forEach(e -> out.line("CONFIG", e));
		}
		return loaded.value();
	}

	/** A webhook URL that passed the checks, or null when kept, skipped or no input is available. Never echoed. */
	private String askWebhook(String forWhat, boolean hasCurrent, boolean optional) {
		String hint = hasCurrent ? ", Enter keeps the current one" : optional ? ", Enter skips" : "";
		for (int attempt = 0; attempt < 5; attempt++) {
			String url = in.read("Paste the webhook URL for " + forWhat + " (input is hidden" + hint + "): ", true);
			if (url == null) {
				if (!optional && !hasCurrent) {
					out.line("CONFIG", "No input available. Set " + Secrets.ENV_WEBHOOK + " or put \"discordWebhookUrl\" in "
							+ files.secretsFile() + ", or run in a terminal.");
				}
				return null;
			}
			url = url.trim();
			if (url.isEmpty()) {
				if (hasCurrent || optional) {
					return null;
				}
				continue;
			}
			String problem = Secrets.validateWebhookUrl(url);
			if (problem != null) {
				out.line("CONFIG", "That is not a webhook URL: " + problem + ". Try again.");
				continue;
			}
			out.line("CONFIG", "Checking the webhook with Discord (nothing is posted)...");
			WebhookCheck check = checkWebhook(http.get(), url);
			out.line("CONFIG", check.message());
			if (!check.ok()) {
				if (check.definitive()) {
					continue;
				}
				String a = in.read("Save it anyway and continue? [y/N]: ", false);
				if (a == null || !a.trim().toLowerCase(Locale.ROOT).startsWith("y")) {
					continue;
				}
			}
			return url;
		}
		out.line("CONFIG", "No valid webhook entered.");
		return null;
	}

	/** Asks for the main thresholds, Enter keeping each current value; saves config.json when anything changed. */
	void askThresholds(BinWatchConfig cfg) throws IOException {
		boolean changed = false;
		Long minProfit = askCoins("Minimum net profit after fees [" + BoardEmbed.coins(cfg.filters.minNetProfit) + "]: ");
		if (minProfit != null) {
			cfg.filters.minNetProfit = minProfit;
			changed = true;
		}
		Double minPct = askNumber("Minimum discount below the estimated resale, % [" + fmt(cfg.filters.minDiscountPercent)
				+ "]: ", 0, 100);
		if (minPct != null) {
			cfg.filters.minDiscountPercent = minPct;
			changed = true;
		}
		Double maxFlips = askNumber("Flips shown in the Discord message (1-20) [" + cfg.board.maxFlips + "]: ", 1, 20);
		if (maxFlips != null) {
			cfg.board.maxFlips = (int) Math.round(maxFlips);
			changed = true;
		}
		if (changed) {
			List<String> errors = cfg.validate();
			if (!errors.isEmpty()) {
				out.line("CONFIG", "Not saved: " + String.join("; ", errors));
				return;
			}
			AtomicFiles.writeString(files.configFile(), ConfigManager.GSON.toJson(cfg));
			out.line("CONFIG", "Saved to " + files.configFile() + " (edit it any time; changes apply within 3 s).");
		}
	}

	private Long askCoins(String prompt) {
		for (int i = 0; i < 3; i++) {
			String a = in.read(prompt, false);
			if (a == null || a.isBlank()) {
				return null;
			}
			Long v = parseCoins(a);
			if (v != null && v >= 0) {
				return v;
			}
			out.line("CONFIG", "Enter an amount like 500k, 1.5m or 2000000.");
		}
		return null;
	}

	private Double askNumber(String prompt, double min, double max) {
		for (int i = 0; i < 3; i++) {
			String a = in.read(prompt, false);
			if (a == null || a.isBlank()) {
				return null;
			}
			try {
				double v = Double.parseDouble(a.trim().replace("%", ""));
				if (v >= min && v <= max) {
					return v;
				}
			} catch (NumberFormatException ignored) {
				// Ask again.
			}
			out.line("CONFIG", "Enter a number from " + fmt(min) + " to " + fmt(max) + ".");
		}
		return null;
	}

	/** {@code 1.5m} → 1500000, {@code 500k} → 500000, {@code 2b}, plain numbers with optional commas. Null if invalid. */
	static Long parseCoins(String s) {
		String t = s.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
		if (t.isEmpty()) {
			return null;
		}
		double mult = 1;
		char last = t.charAt(t.length() - 1);
		if (last == 'k' || last == 'm' || last == 'b') {
			mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
			t = t.substring(0, t.length() - 1);
		}
		try {
			double v = Double.parseDouble(t) * mult;
			return Double.isFinite(v) && v >= 0 && v < 1e15 ? Math.round(v) : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** GET on the webhook URL returns the webhook's details without posting anything. */
	static WebhookCheck checkWebhook(HttpTransport http, String url) {
		try {
			HttpTransport.Response r = http.get(URI.create(url.trim()), Duration.ofSeconds(10));
			int st = r.status();
			if (st == 200) {
				String name = null;
				String channel = null;
				try {
					JsonElement el = JsonParser.parseString(r.body() == null ? "" : r.body());
					if (el.isJsonObject()) {
						JsonObject o = el.getAsJsonObject();
						name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
						channel = o.has("channel_id") && !o.get("channel_id").isJsonNull() ? o.get("channel_id").getAsString() : null;
					}
				} catch (RuntimeException ignored) {
					// Details are optional.
				}
				return new WebhookCheck(true, true, "Webhook OK" + (name == null ? "" : ": \"" + name + "\"")
						+ (channel == null ? "" : " in channel " + channel) + ".");
			}
			if (st == 401 || st == 403 || st == 404) {
				return new WebhookCheck(false, true, "Discord does not accept this webhook (HTTP " + st
						+ "); it may have been deleted. Copy it again.");
			}
			if (st == 429) {
				return new WebhookCheck(false, false, "Discord is rate limiting right now (HTTP 429); it could not be checked.");
			}
			return new WebhookCheck(false, false, "Discord answered HTTP " + st + "; it could not be checked.");
		} catch (IOException e) {
			return new WebhookCheck(false, false, "Could not reach Discord (" + e.getClass().getSimpleName() + ").");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new WebhookCheck(false, false, "Interrupted.");
		} catch (IllegalArgumentException e) {
			return new WebhookCheck(false, true, "The webhook URL is not valid.");
		}
	}

	private void saveSecrets(Secrets s) throws IOException {
		AtomicFiles.writeString(files.secretsFile(), ConfigManager.GSON.toJson(s));
		restrictToOwner(files.secretsFile());
	}

	/** Best effort: owner-only read/write (chmod 600 on Linux/macOS, an owner-only ACL on Windows). */
	static boolean restrictToOwner(Path file) {
		if (!Files.exists(file)) {
			return false;
		}
		try {
			PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
			if (posix != null) {
				posix.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
				return true;
			}
			AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
			if (acl != null) {
				UserPrincipal owner = acl.getOwner();
				AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
						.setPermissions(EnumSet.of(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
								AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_ATTRIBUTES,
								AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_NAMED_ATTRS,
								AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.READ_ACL, AclEntryPermission.WRITE_ACL,
								AclEntryPermission.DELETE, AclEntryPermission.SYNCHRONIZE))
						.build();
				acl.setAcl(List.of(entry));
				return true;
			}
		} catch (IOException | UnsupportedOperationException | SecurityException e) {
			return false;
		}
		return false;
	}

	private static String fmt(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
	}
}

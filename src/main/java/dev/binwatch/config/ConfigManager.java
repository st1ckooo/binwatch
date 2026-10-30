package dev.binwatch.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.binwatch.log.BwLog;
import dev.binwatch.store.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads and validates the configuration and secrets files. An invalid file is never overwritten: the previous (or
 * default) configuration stays active and the problems are reported.
 */
public final class ConfigManager {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final Path configFile;
	private final Path secretsFile;

	public record LoadResult<T>(T value, List<String> errors, boolean createdDefault) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	public ConfigManager(Path configDir) {
		this.configFile = configDir.resolve("config.json");
		this.secretsFile = configDir.resolve("secrets.json");
	}

	public Path configFile() {
		return configFile;
	}

	public Path secretsFile() {
		return secretsFile;
	}

	public LoadResult<BinWatchConfig> loadConfig() {
		try {
			if (!Files.exists(configFile)) {
				BinWatchConfig defaults = new BinWatchConfig();
				AtomicFiles.writeString(configFile, GSON.toJson(defaults));
				BwLog.info("Created default configuration at " + configFile);
				return new LoadResult<>(defaults, List.of(), true);
			}
			String json = Files.readString(configFile, StandardCharsets.UTF_8);
			BinWatchConfig cfg = GSON.fromJson(json, BinWatchConfig.class);
			if (cfg == null) {
				return new LoadResult<>(null, List.of("configuration file is empty"), false);
			}
			List<String> errors = cfg.validate();
			return new LoadResult<>(errors.isEmpty() ? cfg : null, errors, false);
		} catch (JsonParseException e) {
			return new LoadResult<>(null, List.of("configuration is not valid JSON: " + e.getMessage()), false);
		} catch (IOException e) {
			return new LoadResult<>(null, List.of("could not read configuration: " + e), false);
		}
	}

	public LoadResult<Secrets> loadSecrets() {
		Secrets secrets = new Secrets();
		try {
			if (!Files.exists(secretsFile)) {
				AtomicFiles.writeString(secretsFile, GSON.toJson(secrets));
				BwLog.info("Created empty secrets file at " + secretsFile + " (add your Discord webhook URL there)");
			} else {
				Secrets parsed = GSON.fromJson(Files.readString(secretsFile, StandardCharsets.UTF_8), Secrets.class);
				if (parsed != null) {
					secrets = parsed;
				}
			}
		} catch (JsonParseException e) {
			// Never include the parser message: it may quote the secret.
			return new LoadResult<>(secrets, List.of("secrets.json is not valid JSON"), false);
		} catch (IOException e) {
			return new LoadResult<>(secrets, List.of("could not read secrets.json: " + e.getClass().getSimpleName()), false);
		}
		String env = System.getenv(Secrets.ENV_WEBHOOK);
		if (env != null && !env.isBlank()) {
			secrets.discordWebhookUrl = env.trim();
		}
		if (secrets.discordWebhookUrl == null) {
			secrets.discordWebhookUrl = "";
		}
		secrets.discordWebhookUrl = secrets.discordWebhookUrl.trim();
		secrets.discordSummaryWebhookUrl = secrets.discordSummaryWebhookUrl == null ? "" : secrets.discordSummaryWebhookUrl.trim();
		if (!secrets.discordSummaryWebhookUrl.isEmpty()) {
			String problem = Secrets.validateWebhookUrl(secrets.discordSummaryWebhookUrl);
			if (problem != null) {
				secrets.discordSummaryWebhookUrl = "";
				BwLog.warn("Discord summary webhook URL rejected: " + problem + "; using the alert webhook");
			}
		}
		secrets.discordBotToken = secrets.discordBotToken == null ? "" : secrets.discordBotToken.trim();
		if (secrets.discordBotToken.regionMatches(true, 0, "Bot ", 0, 4)) {
			secrets.discordBotToken = secrets.discordBotToken.substring(4).trim();
		}
		if (secrets.hasBotToken()) {
			String problem = Secrets.validateBotToken(secrets.discordBotToken);
			if (problem != null) {
				secrets.discordBotToken = "";
				BwLog.warn("Discord bot token rejected: " + problem);
			}
		}
		if (secrets.hasWebhook()) {
			String problem = Secrets.validateWebhookUrl(secrets.discordWebhookUrl);
			if (problem != null) {
				secrets.discordWebhookUrl = "";
				return new LoadResult<>(secrets, List.of("Discord webhook URL rejected: " + problem), false);
			}
		}
		return new LoadResult<>(secrets, List.of(), false);
	}
}

package dev.binwatch.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * User configuration, stored as {@code config/binwatch/config.json}. See README "Configuration" for every field.
 * The Discord webhook URL is deliberately NOT part of this class; it lives in {@link Secrets}.
 */
public final class BinWatchConfig {
	public static final int CURRENT_VERSION = 1;

	public int configVersion = CURRENT_VERSION;
	public Api api = new Api();
	public Valuation valuation = new Valuation();
	public Fees fees = new Fees();
	public Filters filters = new Filters();
	public Discord discord = new Discord();
	public Chat chat = new Chat();
	public AutoView autoView = new AutoView();
	public Market market = new Market();
	public Summary summary = new Summary();
	public DiscordBot discordBot = new DiscordBot();
	public Storage storage = new Storage();
	public Logging logging = new Logging();
	public Export export = new Export();

	public static final class Api {
		/** Start the public-API collector automatically when the game starts. */
		public boolean enabled = true;
		/** Seconds between full polls. This does not guarantee fresh data: Hypixel caches the endpoint. */
		public int pollIntervalSeconds = 60;
		/** When page 0 shows an unchanged lastUpdated, re-check page 0 after this many seconds (0 = wait a full interval). */
		public int unchangedRecheckSeconds = 10;
		/**
		 * Schedule the next poll no later than when the next snapshot is due (lastUpdated + observed cadence), instead
		 * of a fixed interval that can lock onto a poor phase. Adds a few page-0 requests per minute at most.
		 */
		public boolean alignToSnapshotCadence = true;
		/**
		 * Snapshot-timed polling: sleep until just before the next snapshot is expected, then probe page 0 with cheap
		 * conditional requests (HTTP 304 when unchanged) until it publishes. Replaces the fixed interval above.
		 */
		public boolean snapshotTimedPolling = true;
		/** Start probing this long before the next snapshot is expected. */
		public long preWakeMs = 2000;
		/** Time between the starts of page-0 probes while waiting for a snapshot (the request round trip counts towards it). */
		public long burstIntervalMs = 100;
		/** Stop probing after this long, log it and fall back to a full re-sync poll. */
		public long maxBurstMs = 20_000;
		/** Re-fetch a page that still reports an older snapshot than page 0 up to this many times. */
		public int pageRetries = 3;
		public long staleRetryDelayMs = 250;
		public int maxConcurrentPageRequests = 8;
		public int maxRetries = 3;
		public long retryBaseDelayMs = 1000;
		public long retryMaxDelayMs = 30_000;
		public int connectTimeoutSeconds = 10;
		public int requestTimeoutSeconds = 30;
		/** Also poll the documented /v2/skyblock/auctions_ended endpoint to build a sold-price history. */
		public boolean fetchEndedAuctions = true;
		/** Timestamps up to this far in the future are tolerated as clock skew; beyond it they are invalid. */
		public long clockSkewToleranceMs = 5000;
	}

	public static final class Valuation {
		/** Enabled valuation sources: LOWEST_BIN (current API snapshot) and/or ENDED_AUCTIONS (recorded sales). */
		public List<String> sources = new ArrayList<>(List.of("LOWEST_BIN", "ENDED_AUCTIONS"));
		/** Comparable BIN listings needed for full lowest-BIN confidence. */
		public int targetComparableListings = 5;
		/** Minimum recorded sales before ENDED_AUCTIONS is used at all. */
		public int minSalesSamples = 3;
		/** Recorded sales needed for full sales confidence. */
		public int targetSalesSamples = 10;
		public int salesLookbackHours = 72;
		/** Assumed undercut when relisting, in percent of the estimated market price. */
		public double undercutPercent = 1.0;
		/** A lowest-BIN index older than this is not used. */
		public int maxIndexAgeSeconds = 300;
		/** Discounts above this percentage are treated as suspicious (wrong variant, bait listing) and lower confidence. */
		public double suspiciousDiscountPercent = 80.0;
		/** Whether the reforge is part of the item identity used for comparisons. */
		public boolean includeReforgeInKey = false;
		/** ExtraAttributes keys that make items materially different and therefore form part of the comparison key. */
		public List<String> valueAttributes = new ArrayList<>(List.of(
				"rarity_upgrades", "upgrade_level", "dungeon_item_level", "hot_potato_count", "art_of_war_count",
				"farming_for_dummies_count", "ethermerge", "power_ability_scroll", "skin", "dye_item", "gems",
				"attributes", "talisman_enrichment", "mana_disintegrator_count", "jalapeno_count",
				"wood_singularity_count", "polarvoid", "new_years_cake", "edition", "drill_part_upgrade_module",
				"drill_part_fuel_tank", "drill_part_engine", "model", "ability_scroll", "divan_powder_coating",
				"tuned_transmission", "thunder_charge", "stats_book", "champion_combat_xp", "farmed_cultivating",
				"compact_blocks", "expertise_kills", "hecatomb_s_runs", "toxophilite_combat_xp", "blood_god_kills"));
	}

	public static final class FeeTier {
		/** Upper bound (exclusive) of the listing price for this tier; null means no upper bound. */
		public Long upToPrice;
		public double percent;

		public FeeTier() {
		}

		public FeeTier(Long upToPrice, double percent) {
			this.upToPrice = upToPrice;
			this.percent = percent;
		}
	}

	/** Auction House fees. Hypixel changes these occasionally: verify the current values in game. */
	public static final class Fees {
		public List<FeeTier> listingFeeTiers = new ArrayList<>(List.of(
				new FeeTier(10_000_000L, 1.0),
				new FeeTier(100_000_000L, 2.0),
				new FeeTier(null, 2.5)));
		/** Tax charged when collecting coins from a sold BIN, applied above {@link #claimTaxThreshold}. */
		public double claimTaxPercent = 1.0;
		public long claimTaxThreshold = 1_000_000;
		/** Any extra flat cost per flip (duration fee, transport, etc.). */
		public long extraCostPerFlip = 0;
	}

	public static final class Filters {
		public long minNetProfit = 1_000_000;
		public double minDiscountPercent = 10.0;
		/** 0.0 - 1.0 */
		public double minConfidence = 0.6;
		/** Maximum BIN price to alert on; 0 means no limit. */
		public long maxPurchasePrice = 0;
		/** SkyBlock item ids (exact, case-insensitive) that never alert. */
		public List<String> excludedItemIds = new ArrayList<>();
		/** Case-insensitive substrings of the item name that never alert. */
		public List<String> excludedNameKeywords = new ArrayList<>();
		/**
		 * Alert on auctions that already existed when the collector first saw them (for example the very first
		 * snapshot after starting). Off by default to avoid an alert flood of stale listings.
		 */
		public boolean alertOnPreexisting = false;
	}

	public static final class Discord {
		public boolean enabled = true;
		/** Embed color as RGB integer (default 0x2ECC71). */
		public int embedColor = 0x2ECC71;
		/** Color alerts by item rarity (Legendary gold, Mythic pink...) instead of embedColor when the rarity is known. */
		public boolean colorByRarity = true;
		/** Show an item icon in alerts. Discord fetches the image; only the SkyBlock item id is in the URL. */
		public boolean itemImages = true;
		/** HTTPS icon URL template; {id} is replaced by the SkyBlock item id (pets: PET_<TYPE>). */
		public String itemImageUrl = "https://sky.coflnet.com/static/icon/{id}";
		public int maxAlertsPerMinute = 10;
		public int maxAlertsPerHour = 120;
		public int queueCapacity = 50;
		/** Minimum seconds between two alerts for the same normalized item. */
		public int itemCooldownSeconds = 300;
		public int maxSendAttempts = 3;
		public int requestTimeoutSeconds = 15;
		/** Queued alerts older than this are dropped instead of sent (the opportunity is probably gone). */
		public int maxQueuedAgeSeconds = 120;
	}

	/**
	 * Speed check: open each alerted flip in game with /viewauction and record whether it was still for sale. Only
	 * opens the auction; it never clicks anything, so nothing is ever bought or bid on.
	 */
	public static final class AutoView {
		/** Off by default; toggle at runtime with /binwatch autoview on|off. */
		public boolean enabled = false;
		/** When on Hypixel but not in SkyBlock, send /play skyblock (every 15 s, with /lobby every third try). */
		public boolean autoJoinSkyblock = true;
		/** Minimum time between two /viewauction commands. */
		public long minGapMs = 2000;
		/** Flips older than this (since their page arrived) are recorded as skipped instead of opened. */
		public int maxAgeSeconds = 30;
		/** How long to wait for the auction screen or a chat reply before recording NO_RESPONSE. */
		public long resultTimeoutMs = 5000;
		/**
		 * "Full ready": place the cursor on Buy Item Right Now, then on Confirm, for alerted flips. Moves the cursor
		 * only; every click is yours. Off by default; /binwatch fullready on|off.
		 */
		public boolean fullReady = false;
	}

	/**
	 * Second opinion on flips that pass the local filters: the market value of the exact item from an external price
	 * service (SkyCofl today; the provider is swappable), used for the worth-it check and the listing advice.
	 */
	public static final class Market {
		public boolean enabled = true;
		/** {@code COFL} (sky.coflnet.com). */
		public String provider = "COFL";
		public String baseUrl = "https://sky.coflnet.com";
		/** Only alert flips the market check confirms; otherwise alert with a warning. */
		public boolean requireWorthIt = true;
		/** Alert (marked unchecked) when the price service cannot be reached, instead of dropping the flip. */
		public boolean alertWhenUnavailable = true;
		/** Minimum sales per day of the item (the service's volume figure). */
		public double minDailySales = 1.0;
		/** Reject items expected to take longer than this to sell at the suggested price. */
		public double maxSellHours = 48;
		/** Undercut the market price by this much when suggesting a listing price. */
		public double undercutPercent = 1.0;
		/** Never send more than this many requests per minute (SkyCofl allows 100 per IP). */
		public int maxRequestsPerMinute = 60;
		public long requestTimeoutMs = 3000;
		/**
		 * Looser local bar for flips that SkyCofl then checks: the local estimate only decides what is worth asking
		 * about, and the market check applies filters.minNetProfit at the market price. Never stricter than filters.
		 */
		public long prefilterMinNetProfit = 250_000;
		public double prefilterMinDiscountPercent = 3.0;
		public double prefilterMinConfidence = 0.3;
	}

	/** Profit summary kept up to date in one Discord message (edited in place). */
	public static final class Summary {
		public boolean enabled = true;
		/** Refresh at least this often, and within seconds of a buy or sale. */
		public int updateSeconds = 60;
		/** Number of flips listed in the summary. */
		public int topCount = 10;
	}

	/**
	 * Discord bot run by the mod (token in secrets.json). Each channel id is optional: leave it empty to use the webhook
	 * for alerts and the summary, or to skip trades and log messages. Copy ids with Discord's Developer Mode.
	 */
	public static final class DiscordBot {
		public boolean enabled = true;
		public String alertsChannelId = "";
		public String tradesChannelId = "";
		public String summaryChannelId = "";
		public String logChannelId = "";
		/** Register /profit and /status slash commands (answers are only visible to whoever ran them). */
		public boolean slashCommands = true;
	}

	public static final class Chat {
		/**
		 * Show alerted flips in your own game chat with a clickable [VIEW] (runs /viewauction when you click it) and
		 * [COPY]. Nothing is sent unless you click.
		 */
		public boolean alerts = true;
	}

	public static final class Storage {
		public int retentionDays = 3;
		public long maxApiObservationRows = 1_000_000;
		public int salesRetentionDays = 7;
	}

	public static final class Logging {
		/** DEBUG, INFO, WARN or ERROR */
		public String level = "INFO";
		/** Log every first-seen auction (very verbose). */
		public boolean logEveryFirstSeen = false;
		public int maxFileSizeMb = 5;
		public int maxFiles = 5;
	}

	public static final class Export {
		/** Write CSV exports automatically every {@link #autoExportIntervalMinutes}. */
		public boolean autoExportCsv = false;
		public int autoExportIntervalMinutes = 60;
		/** Maximum rows per CSV file. */
		public int maxRowsPerFile = 200_000;
	}

	/** Returns a list of human readable problems; an empty list means the configuration is valid. */
	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		if (api == null || valuation == null || fees == null || filters == null || discord == null
				|| chat == null || autoView == null || market == null || summary == null || discordBot == null || storage == null || logging == null || export == null) {
			errors.add("one or more configuration sections are missing");
			return errors;
		}
		range(errors, "api.pollIntervalSeconds", api.pollIntervalSeconds, 10, 3600);
		range(errors, "autoView.minGapMs", autoView.minGapMs, 1000, 60_000);
		range(errors, "market.maxRequestsPerMinute", market.maxRequestsPerMinute, 1, 100);
		range(errors, "market.requestTimeoutMs", market.requestTimeoutMs, 500, 30_000);
		range(errors, "summary.updateSeconds", summary.updateSeconds, 15, 3600);
		range(errors, "summary.topCount", summary.topCount, 1, 25);
		if (market.minDailySales < 0 || market.maxSellHours <= 0 || market.undercutPercent < 0 || market.undercutPercent > 50) {
			errors.add("market: minDailySales must be >= 0, maxSellHours > 0 and undercutPercent between 0 and 50");
		}
		if (market.prefilterMinNetProfit < 0 || market.prefilterMinDiscountPercent < 0
				|| market.prefilterMinConfidence < 0 || market.prefilterMinConfidence > 1) {
			errors.add("market.prefilter*: profit and discount must be >= 0, confidence between 0 and 1");
		}
		if (!"COFL".equalsIgnoreCase(String.valueOf(market.provider))) {
			errors.add("market.provider must be COFL (the only provider so far)");
		}
		if (market.baseUrl == null || !market.baseUrl.startsWith("https://")) {
			errors.add("market.baseUrl must be an https:// URL");
		}
		for (String[] ch : new String[][] {{"alertsChannelId", discordBot.alertsChannelId},
				{"tradesChannelId", discordBot.tradesChannelId}, {"summaryChannelId", discordBot.summaryChannelId},
				{"logChannelId", discordBot.logChannelId}}) {
			if (ch[1] != null && !ch[1].isBlank() && !ch[1].trim().matches("\\d{15,25}")) {
				errors.add("discordBot." + ch[0] + " must be a Discord channel id (digits) or empty");
			}
		}
		range(errors, "autoView.maxAgeSeconds", autoView.maxAgeSeconds, 1, 600);
		range(errors, "autoView.resultTimeoutMs", autoView.resultTimeoutMs, 1000, 30_000);
		range(errors, "api.unchangedRecheckSeconds", api.unchangedRecheckSeconds, 0, 3600);
		range(errors, "api.maxConcurrentPageRequests", api.maxConcurrentPageRequests, 1, 8);
		range(errors, "api.preWakeMs", api.preWakeMs, 0, 30_000);
		range(errors, "api.burstIntervalMs", api.burstIntervalMs, 100, 10_000);
		range(errors, "api.maxBurstMs", api.maxBurstMs, 1000, 120_000);
		range(errors, "api.pageRetries", api.pageRetries, 0, 10);
		range(errors, "api.staleRetryDelayMs", api.staleRetryDelayMs, 50, 10_000);
		range(errors, "api.maxRetries", api.maxRetries, 0, 10);
		range(errors, "api.retryBaseDelayMs", api.retryBaseDelayMs, 100, 60_000);
		range(errors, "api.retryMaxDelayMs", api.retryMaxDelayMs, api.retryBaseDelayMs, 600_000);
		range(errors, "api.connectTimeoutSeconds", api.connectTimeoutSeconds, 1, 120);
		range(errors, "api.requestTimeoutSeconds", api.requestTimeoutSeconds, 1, 300);
		range(errors, "api.clockSkewToleranceMs", api.clockSkewToleranceMs, 0, 600_000);
		if (valuation.sources == null || valuation.sources.isEmpty()) {
			errors.add("valuation.sources must name at least one source");
		} else {
			for (String s : valuation.sources) {
				String u = s == null ? "" : s.toUpperCase(Locale.ROOT);
				if (!u.equals("LOWEST_BIN") && !u.equals("ENDED_AUCTIONS")) {
					errors.add("valuation.sources contains unknown source '" + s + "' (use LOWEST_BIN, ENDED_AUCTIONS)");
				}
			}
		}
		range(errors, "valuation.targetComparableListings", valuation.targetComparableListings, 1, 100);
		range(errors, "valuation.minSalesSamples", valuation.minSalesSamples, 1, 1000);
		range(errors, "valuation.targetSalesSamples", valuation.targetSalesSamples, valuation.minSalesSamples, 1000);
		range(errors, "valuation.salesLookbackHours", valuation.salesLookbackHours, 1, 24 * 30);
		range(errors, "valuation.undercutPercent", valuation.undercutPercent, 0, 50);
		range(errors, "valuation.maxIndexAgeSeconds", valuation.maxIndexAgeSeconds, 30, 86_400);
		range(errors, "valuation.suspiciousDiscountPercent", valuation.suspiciousDiscountPercent, 1, 100);
		if (valuation.valueAttributes == null) {
			errors.add("valuation.valueAttributes must be a list");
		}
		if (fees.listingFeeTiers == null || fees.listingFeeTiers.isEmpty()) {
			errors.add("fees.listingFeeTiers must contain at least one tier");
		} else {
			Long prev = null;
			for (int i = 0; i < fees.listingFeeTiers.size(); i++) {
				FeeTier t = fees.listingFeeTiers.get(i);
				if (t == null) {
					errors.add("fees.listingFeeTiers[" + i + "] is null");
					continue;
				}
				range(errors, "fees.listingFeeTiers[" + i + "].percent", t.percent, 0, 50);
				if (t.upToPrice == null && i != fees.listingFeeTiers.size() - 1) {
					errors.add("only the last fee tier may have no upToPrice");
				}
				if (t.upToPrice != null && prev != null && t.upToPrice <= prev) {
					errors.add("fees.listingFeeTiers must be in ascending upToPrice order");
				}
				prev = t.upToPrice;
			}
			if (fees.listingFeeTiers.get(fees.listingFeeTiers.size() - 1).upToPrice != null) {
				errors.add("the last fee tier must have upToPrice null (no upper bound)");
			}
		}
		range(errors, "fees.claimTaxPercent", fees.claimTaxPercent, 0, 50);
		range(errors, "fees.claimTaxThreshold", fees.claimTaxThreshold, 0, Long.MAX_VALUE);
		range(errors, "fees.extraCostPerFlip", fees.extraCostPerFlip, 0, Long.MAX_VALUE);
		range(errors, "filters.minDiscountPercent", filters.minDiscountPercent, 0, 100);
		range(errors, "filters.minConfidence", filters.minConfidence, 0, 1);
		range(errors, "filters.maxPurchasePrice", filters.maxPurchasePrice, 0, Long.MAX_VALUE);
		if (filters.excludedItemIds == null || filters.excludedNameKeywords == null) {
			errors.add("filters.excludedItemIds and filters.excludedNameKeywords must be lists");
		}
		range(errors, "discord.embedColor", discord.embedColor, 0, 0xFFFFFF);
		range(errors, "discord.maxAlertsPerMinute", discord.maxAlertsPerMinute, 1, 30);
		range(errors, "discord.maxAlertsPerHour", discord.maxAlertsPerHour, 1, 1000);
		range(errors, "discord.queueCapacity", discord.queueCapacity, 1, 1000);
		range(errors, "discord.itemCooldownSeconds", discord.itemCooldownSeconds, 0, 86_400);
		range(errors, "discord.maxSendAttempts", discord.maxSendAttempts, 1, 10);
		range(errors, "discord.requestTimeoutSeconds", discord.requestTimeoutSeconds, 1, 120);
		range(errors, "discord.maxQueuedAgeSeconds", discord.maxQueuedAgeSeconds, 5, 3600);
		if (discord.itemImages && (discord.itemImageUrl == null || !discord.itemImageUrl.startsWith("https://")
				|| !discord.itemImageUrl.contains("{id}"))) {
			errors.add("discord.itemImageUrl must start with https:// and contain {id}");
		}
		range(errors, "storage.retentionDays", storage.retentionDays, 1, 365);
		range(errors, "storage.maxApiObservationRows", storage.maxApiObservationRows, 10_000, 50_000_000);
		range(errors, "storage.salesRetentionDays", storage.salesRetentionDays, 1, 365);
		if (logging.level == null || !List.of("DEBUG", "INFO", "WARN", "ERROR").contains(logging.level.toUpperCase(Locale.ROOT))) {
			errors.add("logging.level must be DEBUG, INFO, WARN or ERROR");
		}
		range(errors, "logging.maxFileSizeMb", logging.maxFileSizeMb, 1, 100);
		range(errors, "logging.maxFiles", logging.maxFiles, 1, 50);
		range(errors, "export.autoExportIntervalMinutes", export.autoExportIntervalMinutes, 1, 10_080);
		range(errors, "export.maxRowsPerFile", export.maxRowsPerFile, 100, 5_000_000);
		return errors;
	}

	private static void range(List<String> errors, String name, double value, double min, double max) {
		if (Double.isNaN(value) || value < min || value > max) {
			errors.add(name + " must be between " + fmt(min) + " and " + fmt(max) + " (was " + fmt(value) + ")");
		}
	}

	private static String fmt(double d) {
		return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
	}

	public boolean sourceEnabled(String name) {
		return valuation.sources.stream().anyMatch(s -> s.equalsIgnoreCase(name));
	}
}

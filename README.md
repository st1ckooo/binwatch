# BIN Watch

BIN Watch is a client-side Fabric mod for **Minecraft Java Edition 26.2** that watches Hypixel SkyBlock Buy It Now (BIN) auctions. It:

- polls the public Hypixel auction API and records when each auction UUID first appears,
- estimates possible resale value and profit,
- sends qualifying alerts to a Discord webhook.

It also comes as a **[standalone console app](#standalone-app-no-minecraft)** that runs the same engine without Minecraft and keeps one live "AH Flipper" message in Discord.

**It never buys or bids.** It never clicks a slot, modifies packets, or uses private or undocumented endpoints. The only commands it ever sends for you are `/viewauction`, `/play skyblock` and `/lobby`, and only while the optional [auto-open](#auto-open-and-speed-check) is switched on (it is off by default). Every alert is an *estimate*, not a guaranteed profit. Verify any listing in game before you act on it.

---

## Contents

1. [Verified target](#verified-target)
2. [What the mod does](#what-the-mod-does)
3. [Public API collector](#public-api-collector)
4. [Does Minecraft need to be running?](#does-minecraft-need-to-be-running)
5. [Timing: how delays are calculated](#timing-how-delays-are-calculated)
6. [Flip valuation](#flip-valuation)
7. [Market check and listing advice](#market-check-and-listing-advice)
8. [Discord alerts](#discord-alerts)
9. [Profit tracking](#profit-tracking)
   - [Discord bot](#discord-bot)
10. [Installation](#installation)
11. [Commands and GUI](#commands-and-gui)
12. [Configuration](#configuration)
13. [Data, exports and reports](#data-exports-and-reports)
14. [Standalone app (no Minecraft)](#standalone-app-no-minecraft)
15. [Building and testing from source](#building-and-testing-from-source)
16. [Troubleshooting](#troubleshooting)

---

## Verified target

Checked on 2026-10-29 against `meta.fabricmc.net`, the Fabric Maven repository, and the official `FabricMC/fabric-example-mod` `26.2` branch.

| Component | Version |
|---|---|
| Minecraft Java Edition | **26.2** (stable) |
| Fabric Loader | 0.19.5 or newer (built and run against 0.19.5) |
| Fabric API | 0.161.0+26.2 |
| Fabric Loom (Gradle plugin) | 1.19.3, plugin id `net.fabricmc.fabric-loom` |
| Mappings | none: Minecraft 26.x ships unobfuscated, so Loom uses Mojang's names directly |
| Java | **25** (required by Minecraft 26.x) |
| Gradle | 9.8.2 (wrapper included) |
| Bundled library | H2 2.5.252 (embedded database, nested in the JAR) |

The Minecraft classes used (`GuiGraphicsExtractor`, `Gui.setScreen`, Fabric `ClientCommands`) were checked against the 26.2 jars, not taken from older versions.

Hypixel accepts current Minecraft versions, but whether it accepts 26.2 on any given day is Hypixel's decision. The API collector does not depend on it.

---

## What the mod does

```
 public API ──poll──► API collector ──first seen──► H2 database ──► timing statistics, CSV, reports
                          │
                          ▼
                 valuation + filters ──► Discord, chat
```

The mod reads only the public API. It does not read or change anything the game client receives.

---

## Public API collector

- Uses only the documented, key-less endpoints `https://api.hypixel.net/v2/skyblock/auctions` (paginated) and `/v2/skyblock/auctions_ended` (for sold-price history).
- Reads page 0, then pages `1 … totalPages-1`. By default it fetches 8 pages at a time (configurable, 1–8) over kept-alive, gzip-compressed connections. A page that still reports an older `lastUpdated` than page 0 (a stale CDN copy) is fetched again after 250 ms, up to 3 times.
- Each page is diffed against the auctions already known and its new listings are valued and alerted straight away, without waiting for the remaining pages.
- Page 0 is parsed while it downloads. Hypixel lists the newest auctions first (measured live: every auction started in the last 60 s sat within the first ~6% of page 0), so those are valued and alerted as soon as the start of the page has arrived, while the remaining ~94% is still downloading. The rest of the page is then processed as usual; nothing is reported twice.
- Validates every response (`success`, `page`, `totalPages`, `lastUpdated`, `auctions`). Malformed entries are skipped and counted, not guessed.
- Retries with exponential backoff and full jitter, a bounded number of times. It honours HTTP 429 and `Retry-After` (seconds or HTTP date), and handles 404/422 (page gone), 503 (not populated), timeouts, 5xx and malformed JSON.
- Grades each collection as follows:
  - `COMPLETE`: every page retrieved, and all pages report the same `lastUpdated` and `totalPages`.
  - `INCONSISTENT`: every page retrieved, but they disagree.
  - `INCOMPLETE`: some pages failed.

  Only `COMPLETE` is treated as a verified snapshot.
- **Snapshot-timed polling** (default). It learns three things:
  - the snapshot interval: a rolling average, about 60 s;
  - how long after its `lastUpdated` stamp Hypixel actually publishes a snapshot: about 9 s, measured live;
  - the local clock's offset from the API's `Date` header.

  It sleeps until 2 s before the next snapshot is due. Then it starts a probe of page 0 every 100 ms with `If-Modified-Since`. An unchanged page costs only a header-only `304 Not Modified` response, not the 2.3 MB page.
  - As soon as page 0 changes, it fetches the other pages.
  - If no snapshot appears within 20 s (Hypixel occasionally skips one), it logs that, probes every `unchangedRecheckSeconds` in case it is just late, and bursts again on time for the following one. A skipped snapshot does not change the learned interval.
  - After the computer sleeps or the network drops for more than an interval, it re-syncs with a full poll.
  - Network errors back off 1 s, 2 s, then 5 s.
  - The first snapshot after start-up only fills state: everything in it is pre-existing, so it never alerts.
- With `snapshotTimedPolling: false`, it polls on a fixed `pollIntervalSeconds` and re-checks page 0 every `unchangedRecheckSeconds` while `lastUpdated` is unchanged.

**What it can detect:** auctions present in the public snapshot at the time of a poll.

**What it cannot detect:**
- Auctions that were listed and sold between two snapshots.
- The exact time Hypixel published an auction. The probes do pin down when each *snapshot* went live, to within half a probe gap (see [Measuring from publication](#measuring-from-publication)).

The responses are served through a CDN cache (`Cache-Control: max-age=60`). The diagnostics show the `Age` header. **The polling interval does not guarantee fresh data or detection within that interval.**

### "Newly observed" is not "newly created"

The first snapshot after starting contains tens of thousands of auctions that already existed. Those rows are stored with `observation_kind = PREEXISTING_AT_FIRST_SIGHT`.

An auction counts as `NEW_DURING_MONITORING` only when its recorded start time is after the current collector session started. In other words, it was listed while the collector was running. Only those auctions are used in delay statistics.

During live testing, the CDN often served pages from two snapshot versions in one poll. Such polls are graded `INCONSISTENT`, but they do not demote genuinely new auctions. By default, pre-existing auctions never alert (`filters.alertOnPreexisting`).

---

## Does Minecraft need to be running?

| Part | Needs Minecraft running? | Needs to be on Hypixel? | Needs the Auction House open? |
|---|---|---|---|
| API collector | Yes in the mod; **no** with the [standalone app](#standalone-app-no-minecraft) | No; it runs from the title screen too | No |
| Flip scoring and Discord alerts | Yes in the mod; **no** with the standalone app | No | No |
| Reports and CSV export | Yes (commands) | No | No |
| Auto-open, profit banner, full ready, trade tracking | Yes: these act on the game, so they only exist in the mod | Yes | Yes |

---

## Timing: how delays are calculated

All timestamps are stored in UTC epoch milliseconds.

```
apiObservedDelayMs = firstApiObservedAtUtc - auctionStartTimeUtc
```

- `firstApiObservedAtUtc` is captured when the page body containing the UUID has been fully received. It is captured before valuation or Discord work.
- A delay is shown only when both timestamps are present and plausible, and the observation is not before the recorded start. Otherwise it reads "Unavailable (…reason…)". **Negative durations are never displayed.**
- Discord labels read like `API observed 1m 12s after recorded auction start`. The mod never calls this a "publication delay".

### Why recorded start time is not API publication time

The auction's `start` field is when the auction was created. The API publishes snapshots on its own schedule (`lastUpdated`), a CDN may serve a cached copy, and this mod polls at intervals. The API delay therefore includes all of these:

1. the wait for Hypixel's next snapshot,
2. CDN cache age,
3. the gap until this monitor's next poll,
4. download time.

It is an upper bound on how quickly *this monitor* saw the auction, not a measurement of Hypixel's publication delay. `lastUpdated` is kept for diagnostics but is not treated as each auction's publication time.

### Measured live behaviour (development client, 2026-10-29)

These are observations from two short runs. They are not guarantees, and Hypixel or the CDN can behave differently at other times.

| | Run 1 (fixed 60 s interval) | Run 2 (`alignToSnapshotCadence: true`) |
|---|---|---|
| Snapshot cadence (`lastUpdated` steps) | 60 s | 60 s |
| Poll landed after `lastUpdated` | ~62 s | ~10 s |
| CDN `Age` of page 0 | ~50 s | 2–3 s |
| Snapshot grading | mostly `INCONSISTENT` | 5 of 6 `COMPLETE` |
| Full snapshot fetch (44 pages, ~43,600 auctions) | 6.3 s | 3.7–5.4 s |

In run 2, 672 auctions were first seen as `NEW_DURING_MONITORING`. Their API observed delay was:
- median **56 s**,
- p90 **83 s**,
- minimum **18 s**, maximum **90 s**.

Even the newest auction in a fresh snapshot was already about 18–30 s old when it was seen. Most of the delay therefore comes from Hypixel's 60-second snapshot cycle, not from the CDN or polling. No polling setting can push the API source below that floor.

### Measuring from publication

Hypixel stamps each snapshot with `lastUpdated`, but only makes it available about 7.5–9.5 s later (measured live on 2026-10-30). Nobody reading the public API can see a snapshot before that. Services that quote "about 1 second" are measuring from that publication moment, not from `lastUpdated`.

BIN Watch measures the same way. During a burst, the last probe that still got `304 Not Modified` and the first that got the new page bracket the moment the snapshot went live. The midpoint of that bracket (plus half a round trip) is the publish estimate, accurate to within half the gap between the two probes (about ±50 ms at 100 ms probes). Each snapshot then logs:

```
Snapshot lu-… timing: Hypixel published it 7.9s after lastUpdated, at … (+/-50 ms); since publish: request sent +60 ms,
first byte +190 ms, page 0 downloaded +520 ms, page 0 processed +560 ms
```

and each delivered alert logs `page`, `queued`, `POST sent` and `delivered`, all in milliseconds since publication, plus how long the Discord POST took. A snapshot found by the first probe of a burst (or by a plain poll) has no bracket, so it has no publish estimate; the embed then falls back to the `lastUpdated` line.

---

## Flip valuation

The valuation is deliberately conservative and transparent:

1. **Item identity.** The comparison key is strict: SkyBlock id, rarity, every enchantment and level, stars, recombobulation, and each attribute listed in `valuation.valueAttributes` (potato books, gemstones, Kuudra attributes, skins, dyes, editions, and others). Pets use type, rarity, level, held item and skin. Reforge is excluded unless `includeReforgeInKey` is true. Different variants are never compared.
2. **`LOWEST_BIN`.** The cheapest *other* BIN listing of the exact same key in the latest `COMPLETE` or `INCONSISTENT` API snapshot, excluding the candidate itself. It is not used when older than `maxIndexAgeSeconds`.
3. **`ENDED_AUCTIONS`.** The median of BIN sales of the same key recorded from `/auctions_ended` while the mod was running. It needs at least `minSalesSamples` sales.
4. **Estimate.** The lower of the available sources, minus `undercutPercent`.
5. **Fees.** The listing fee tiers (1% < 10M, 2% < 100M, 2.5% above) and the 1% claim tax above 1M are configurable. *These are Hypixel's fees as last known; verify them in game.*
6. **Confidence (0–1).**
   - With lowest BIN only: up to 0.70, based on the number of comparable listings.
   - With sales only: up to 0.60.
   - With both: a mix of the two, plus how well they agree.

   Confidence is reduced when the sources disagree by more than 2×, or when the discount exceeds `suspiciousDiscountPercent` (possible wrong variant or bait listing).
7. **Filters.** An alert needs all of these:
   - a BIN auction,
   - a valid valuation,
   - confidence ≥ `minConfidence`,
   - net profit ≥ `minNetProfit`,
   - discount ≥ `minDiscountPercent`,
   - price ≤ `maxPurchasePrice` (0, the default, means no limit),
   - not excluded by item id or name keyword,
   - not pre-existing (unless enabled).

The public auction endpoint alone is **not** a trustworthy price for every item. Rare or unique variants often have no comparables. They get no valid valuation and never alert.

---

## Market check and listing advice

The local valuation only knows the current AH snapshot and recent sales seen by this client. Before a flip is alerted, it gets a second opinion from an external price service: **SkyCofl** (`sky.coflnet.com`) today. The provider sits behind one small interface (`market/PriceProvider`), so another service (for example skymarket.dev) can replace it later without touching the rest.

How it works:

1. A flip passes the local filters (max price, blacklist and so on). With the market check on, the profit, discount and confidence bar here is looser (`market.prefilterMinNetProfit` 250k, `prefilterMinDiscountPercent` 3%, `prefilterMinConfidence` 0.3). The local estimate only picks what is worth asking SkyCofl about; it often misjudges items it has few comparables for.
2. Its exact item data (the auction's `item_bytes`, so enchantments, stars, reforge and attributes count) is sent to `POST /api/price/nbt`. SkyCofl answers with the lowest BIN, the median sale price and the sales volume for items like it. This takes about 0.15–0.25 s.
3. The **listing advice** is worked out from that:
   - **List price:** the median, or the cheapest other BIN if that is lower, minus `market.undercutPercent` (1%). If the cheapest BIN is this very auction, it is ignored.
   - **Duration:** the shortest of 1, 6, 12, 24 or 48 hours that covers at least twice the expected time to sell (24 h ÷ sales per day).
   - **Profit:** list price − listing fee − claim tax − purchase price.
4. The **worth-it check** passes when the profit at that list price is at least `filters.minNetProfit`, the item sells at least `market.minDailySales` times a day, and it is expected to sell within `market.maxSellHours`.
5. With `market.requireWorthIt` (default), only flips that pass are alerted. Otherwise they are alerted with a "Not worth it" line.

If SkyCofl cannot be reached, the flip is still alerted with a "No market data" note, but only if it also clears the strict local filters (`filters.minNetProfit`, `minDiscountPercent`, `minConfidence`). Turn that off with `market.alertWhenUnavailable: false`.

Requests stay inside SkyCofl's limits (100 per minute and 30 per 10 s per IP): at most 25 in any 10 s and `market.maxRequestsPerMinute` (60) per minute, and a 429 pauses all requests for its `Retry-After`. Flips from the same snapshot are checked in parallel, so none waits behind another. Only flips that passed the local pre-filter are checked, typically a few per snapshot. SkyCofl asks for attribution, so alerts and the summary say "Prices provided by SkyCofl". Commercial use of their API needs their Premium+ plan.

---

## Discord alerts

Each alert is one compact embed titled **Potential BIN Flip**:

- **Header:** item name (with stars), rarity, recombobulated and stack size, plus the item's icon.
- **Profit:** estimated net profit after fees, with the discount, in bold green (an `ansi` code block; the Discord phone app may show it without colour).
- **AH price:** the BIN price.
- **Suggested price:** the market check's list price, or the local estimate when there is no market check (an estimate, never guaranteed; the footer says so).
- **Market (SkyCofl):** median, lowest BIN and sales per day, then the advice, for example `List at 9,405,000 for 6h -> est. +1,216,900 after fees (sells in ~2h 0m 0s)`. A "Not worth it" or "No market data" line appears when relevant.
- **Enchantments:** all of them, with roman numerals. Ultimate enchantments come first, in bold.
- **Upgrades:** reforge, hot potato and fuming books, Art of War, ability scrolls, power scroll, dye, skin, enrichment, runes, gemstones, Kuudra attributes. Pets show held item, skin and candy instead.
- **Found on API:** two lines:
  - how long after the recorded auction start the API showed the auction, for example `API observed 1m 12s after recorded auction start`. This includes Hypixel's own wait for the next 60-second snapshot.
  - how quickly BIN Watch sent the alert after Hypixel published the snapshot, and how late Hypixel published it, for example `Sent 0.6s after Hypixel published the snapshot (Hypixel published it 7.9s after its lastUpdated)`. When the publish moment was not bracketed by probes, it reads `Fetched 9.4s after its API snapshot (lastUpdated)` instead, which includes Hypixel's own publishing delay.
- **Open in game:** the `/viewauction <uuid>` command in a code block, ready to copy.

Profit, discount, confidence, valuation source and the filter reasons are no longer in the embed. They are still used for filtering, and are shown in `/binwatch top`, the log and the CSV exports.

The embed color follows the item's rarity (Legendary gold, Mythic pink and so on) unless `discord.colorByRarity` is `false`; then `discord.embedColor` is used.

**Item icons.** The icon is loaded by Discord, not by your game, from `discord.itemImageUrl` (default: the community icon service at `sky.coflnet.com`). The only information in that URL is the SkyBlock item id, for example `HYPERION` or `PET_GOLDEN_DRAGON`. Set `discord.itemImages` to `false` to send alerts without an image. If the icon service has no image for an item, Discord simply shows none.

Run `/binwatch testwebhook preview` to send a sample alert, clearly marked `[TEST DATA]`, to see the layout.

### In-game chat alerts

Every alerted flip also appears in your own chat:

`[BIN Watch] Flip: Heroic Hyperion 900M → est. +93.95M (12.6%, conf 70%) [VIEW] [COPY]`

- **[VIEW]** runs `/viewauction <uuid>` when *you* click it, which opens the auction. Minecraft may first ask you to confirm the command.
- **[COPY]** copies the command to your clipboard.

Showing the message sends nothing to the server, and nothing is bought. Turn chat alerts off with `chat.alerts: false`.

No auction URL is ever constructed.

Delivery details:
- Delivery is confirmed only when Discord answers 2xx. The mod posts with `?wait=true` so Discord confirms the message was created.
- The queue is bounded; the lowest-profit alert is dropped first. Limits per minute and per hour apply, and each item has a cooldown.
- HTTP 429 `retry_after`/`Retry-After` is honoured, and retries are bounded.
- Each auction is alerted at most once, ever. This is stored in the database, so it survives restarts. Alerts still queued when the game closes are marked `EXPIRED` on the next start rather than resent late.
- Discord problems never block or crash the collectors.

### Configure the webhook safely

1. In Discord: Server Settings → Integrations → Webhooks → New Webhook → Copy Webhook URL.
2. Start the game once with the mod installed. This creates `.minecraft/config/binwatch/secrets.json`.
3. Edit that file:
   ```json
   {
     "discordWebhookUrl": "https://discord.com/api/webhooks/000000000000000000/REPLACE_WITH_YOUR_TOKEN",
     "discordSummaryWebhookUrl": "",
     "discordBotToken": ""
   }
   ```
   Alternatively, set the environment variable `BINWATCH_DISCORD_WEBHOOK_URL`.
4. Run `/binwatch reload`, then `/binwatch testwebhook`.

The URL is validated (https, discord.com, `/api/webhooks/<id>/<token>`). It is never logged, displayed, stored in the database, or included in exports. Every log line and chat message passes through a redactor. `secrets.json` is in `.gitignore`. The build and the tests never contact a real webhook; they use a scripted fake transport.

---

## Profit tracking

BIN Watch reads your own Auction House chat messages and keeps a ledger of your flips. You still buy, list and collect yourself: nothing is clicked or sent for you.

| Chat message | What happens |
|---|---|
| `You purchased <item> for <price> coins!` | A flip is opened. If BIN Watch alerted that item in the last 30 minutes, it is linked to the alert, and chat shows the advice: `Bought X for 9M. List at 9,405,000 for 6h -> est. +1.2M after fees` |
| `BIN Auction started for <item>!` | The flip is marked listed |
| `[Auction] <buyer> bought <item> for <price> coins` | The flip is marked sold; profit = sale price − listing fee − claim tax − purchase price |
| `You collected <coins> coins from selling <item> to <buyer> in an auction!` | The flip is marked collected; profit uses the coins actually received |

Items are matched by name, oldest open flip first. A sale with no tracked purchase is stored as untracked and left out of the profit figures. Chat lines that look auction-related but match none of these are written to `binwatch.log`, so new wording can be spotted.

**Live Discord summary.** One summary message is kept up to date in Discord. It is posted once, then edited in place within about 10 s of a purchase or sale, and at least every `summary.updateSeconds` (60) when something changed. It shows:
- profit for the last hour, the last 24 hours and overall, and profit per hour;
- flips sold, average profit and average holding time;
- open flips: count, coins invested and expected profit;
- the top `summary.topCount` (10) flips: ✅ sold with real profit, ⏳ open with expected profit.

The message id is stored in the database, so a restart keeps editing the same message. If you delete it in Discord, a new one is posted. To keep it from being buried under alerts, give it its own channel: add `"discordSummaryWebhookUrl"` to `secrets.json` with a webhook for that channel.

`/binwatch profit` shows the same figures in game; `/binwatch summary` updates the Discord message immediately.

### Discord bot

Instead of webhooks, BIN Watch can run a Discord bot from inside the mod and keep things in separate channels:

| Channel (`discordBot.*`) | What goes there |
|---|---|
| `alertsChannelId` | Flip alerts (the same embed as the webhook) |
| `tradesChannelId` | One message per purchase, listing, sale and collection, with the profit |
| `summaryChannelId` | The live profit summary, edited in place |
| `logChannelId` | "BIN Watch started", plus warnings and errors (at most 20 per 10 minutes) |

Any channel left empty falls back to the webhook (alerts, summary) or is skipped (trades, log). While the game runs, the bot shows as online ("Watching the Auction House") and answers two slash commands. `/profit` shows the profit summary and `/status` shows the same lines as `/binwatch status`. Only the person who ran the command sees the answer, and by default only server administrators can use them. Turn them off with `discordBot.slashCommands: false`. The bot asks Discord for no privileged intents, so it cannot read messages.

Setup (one time):
1. Go to <https://discord.com/developers/applications> → **New Application** → **Bot** → **Reset Token**, and copy the token.
2. **OAuth2 → URL Generator:** tick `bot` and `applications.commands`, then the permissions **View Channels**, **Send Messages** and **Embed Links**. Open the generated URL and add the bot to your server.
3. In Discord, turn on **Settings → Advanced → Developer Mode**. Right-click each channel → **Copy Channel ID**.
4. Put the token in `secrets.json` as `"discordBotToken"`, and the channel ids in `config.json` under `discordBot`.

The token is validated, sent only in the `Authorization` header to `discord.com`, and redacted from every log line and chat message. Like the webhook, it is never logged, stored in the database or exported.

---

## Installation

1. Install **Java 25**, for example Microsoft Build of OpenJDK 25 or Eclipse Temurin 25.
2. Install the **Fabric Loader 0.19.5 or newer** for **Minecraft 26.2**:
   - Download the installer from <https://fabricmc.net/use/installer/>.
   - Choose *Client*, Minecraft version **26.2**, loader **0.19.5** or newer, then *Install*.
3. Download **Fabric API 0.161.0+26.2** from Modrinth (<https://modrinth.com/mod/fabric-api>) or CurseForge, and put it in `.minecraft/mods/`.
4. Copy `binwatch-1.0.0.jar` into `.minecraft/mods/`.
   - On Windows, `.minecraft` is `%APPDATA%\.minecraft`.
5. Start the game with the **Fabric Loader 26.2** profile in the Minecraft Launcher.
6. Configure the Discord webhook as described above (optional).

Files created at first start:

| Path (under `.minecraft/`) | Contents |
|---|---|
| `config/binwatch/config.json` | settings (documented below) |
| `config/binwatch/secrets.json` | Discord webhook URL (keep private) |
| `binwatch/binwatch.mv.db` | H2 database: first-seen history, alerts, sales |
| `binwatch/exports/` | CSV exports and timing reports |
| `logs/binwatch/binwatch.log` | rotating BIN Watch log (5 × 5 MB by default) |

---

## Commands and GUI

All commands are **client-side** Fabric commands. They run locally and are never sent to Hypixel.

| Command | Effect |
|---|---|
| `/binwatch` or `/binwatch help` | List commands |
| `/binwatch status` | Collector state, poll mode, last successful poll, last snapshot (`lastUpdated`, completeness, pages), counts, Discord |
| `/binwatch start` / `/binwatch stop` | Start or stop the API collector |
| `/binwatch top [1-20]` | Best current candidates, with pass/reject reasons and `/viewauction` commands |
| `/binwatch stats [hours]` | API timing statistics (default 24 h) |
| `/binwatch report [hours]` | Same as `stats`, and also writes `binwatch/exports/report_<time>.txt` with diagnostics |
| `/binwatch export` | Write CSV exports |
| `/binwatch testwebhook` | Send one clearly labelled test message to your webhook |
| `/binwatch testwebhook preview` | Send a sample flip alert marked `[TEST DATA]` so you can see the alert layout |
| `/binwatch reload` | Reload `config.json` and `secrets.json`. An invalid config is rejected and the running one is kept |
| `/binwatch diag` | Health diagnostics: request, retry and 429 counters, CDN cache age, clock offset, price index, row counts, log path. Secrets are never shown |
| `/binwatch gui` | Status screen with Start/Stop API, Test webhook, Export CSV, Reload config, Close |
| `/binwatch autoopen on` / `off` / `status` | Switch [auto-open](#auto-open-and-speed-check) on or off, or show its speed-check results (`autoview` is the same command) |
| `/binwatch fullready on` / `off` / `status` | Switch [full ready](#auto-open-and-speed-check) cursor placement on or off |
| `/binwatch profit` | Profit from your tracked flips: last hour, 24 h, overall, open flips, top flips |
| `/binwatch summary` | Update the live profit summary in Discord now |
| `/binwatch testmode` | Run the full pipeline on synthetic `[TEST]` data in a private in-memory database. No network, no Discord |

Start/stop is a runtime switch. Its startup default comes from `api.enabled` in the config.

---

## Configuration

Copy-paste example (`config/binwatch/config.json`; these are the defaults):

```json
{
  "configVersion": 1,
  "api": {
    "enabled": true,
    "pollIntervalSeconds": 60,
    "unchangedRecheckSeconds": 10,
    "alignToSnapshotCadence": true,
    "snapshotTimedPolling": true,
    "preWakeMs": 2000,
    "burstIntervalMs": 100,
    "maxBurstMs": 20000,
    "pageRetries": 3,
    "staleRetryDelayMs": 250,
    "maxConcurrentPageRequests": 8,
    "maxRetries": 3,
    "retryBaseDelayMs": 1000,
    "retryMaxDelayMs": 30000,
    "connectTimeoutSeconds": 10,
    "requestTimeoutSeconds": 30,
    "fetchEndedAuctions": true,
    "clockSkewToleranceMs": 5000
  },
  "valuation": {
    "sources": ["LOWEST_BIN", "ENDED_AUCTIONS"],
    "targetComparableListings": 5,
    "minSalesSamples": 3,
    "targetSalesSamples": 10,
    "salesLookbackHours": 72,
    "undercutPercent": 1.0,
    "maxIndexAgeSeconds": 300,
    "suspiciousDiscountPercent": 80.0,
    "includeReforgeInKey": false,
    "valueAttributes": ["rarity_upgrades", "upgrade_level", "dungeon_item_level", "hot_potato_count"]
  },
  "fees": {
    "listingFeeTiers": [
      { "upToPrice": 10000000, "percent": 1.0 },
      { "upToPrice": 100000000, "percent": 2.0 },
      { "upToPrice": null, "percent": 2.5 }
    ],
    "claimTaxPercent": 1.0,
    "claimTaxThreshold": 1000000,
    "extraCostPerFlip": 0
  },
  "filters": {
    "minNetProfit": 1000000,
    "minDiscountPercent": 10.0,
    "minConfidence": 0.6,
    "maxPurchasePrice": 0,
    "excludedItemIds": [],
    "excludedNameKeywords": [],
    "alertOnPreexisting": false
  },
  "discord": {
    "enabled": true,
    "embedColor": 3066993,
    "colorByRarity": true,
    "itemImages": true,
    "itemImageUrl": "https://sky.coflnet.com/static/icon/{id}",
    "maxAlertsPerMinute": 10,
    "maxAlertsPerHour": 120,
    "queueCapacity": 50,
    "itemCooldownSeconds": 300,
    "maxSendAttempts": 3,
    "requestTimeoutSeconds": 15,
    "maxQueuedAgeSeconds": 120
  },
  "chat": {
    "alerts": true
  },
  "autoView": { "enabled": false, "autoJoinSkyblock": true, "minGapMs": 2000, "maxAgeSeconds": 30, "resultTimeoutMs": 5000, "fullReady": false },
  "market": {
    "enabled": true,
    "provider": "COFL",
    "baseUrl": "https://sky.coflnet.com",
    "requireWorthIt": true,
    "alertWhenUnavailable": true,
    "minDailySales": 1.0,
    "maxSellHours": 48,
    "undercutPercent": 1.0,
    "maxRequestsPerMinute": 60,
    "requestTimeoutMs": 3000,
    "prefilterMinNetProfit": 250000,
    "prefilterMinDiscountPercent": 3.0,
    "prefilterMinConfidence": 0.3
  },
  "summary": { "enabled": true, "updateSeconds": 60, "topCount": 10 },
  "discordBot": {
    "enabled": true,
    "alertsChannelId": "",
    "tradesChannelId": "",
    "summaryChannelId": "",
    "logChannelId": "",
    "slashCommands": true
  },
  "storage": { "retentionDays": 3, "maxApiObservationRows": 1000000, "salesRetentionDays": 7 },
  "logging": { "level": "INFO", "logEveryFirstSeen": false, "maxFileSizeMb": 5, "maxFiles": 5 },
  "export": { "autoExportCsv": false, "autoExportIntervalMinutes": 60, "maxRowsPerFile": 200000 }
}
```

`valueAttributes` is shortened above. The default list has 35 entries. See [config.example.json](config.example.json) or the file the mod generates.

| Setting | Meaning |
|---|---|
| `api.enabled` | Start the API collector at game start |
| `api.pollIntervalSeconds` | Seconds between full polls (10–3600) |
| `api.unchangedRecheckSeconds` | Re-check page 0 while `lastUpdated` is unchanged (0 = wait a full interval) |
| `api.alignToSnapshotCadence` | Schedule the next poll for when the next snapshot is due (`lastUpdated` + observed cadence) instead of a fixed phase. At most a few extra page-0 requests per minute |
| `api.snapshotTimedPolling` | Sleep until just before each snapshot is due, then probe page 0 with cheap conditional requests until it publishes (see Source A). `false` uses the fixed interval |
| `api.preWakeMs` / `api.burstIntervalMs` / `api.maxBurstMs` | Start probing this long before the expected snapshot (2000), start a probe this often (100, minimum 100; the round trip counts towards it), give up after this long (20000) |
| `api.pageRetries` / `api.staleRetryDelayMs` | Re-fetch a page still showing an older snapshot up to this many times (3), this long apart (250 ms) |
| `api.maxConcurrentPageRequests` | Pages fetched in parallel (1–8, default 8). Takes effect when the collector is next started |
| `api.maxRetries`, `retryBaseDelayMs`, `retryMaxDelayMs` | Bounded exponential backoff with jitter |
| `api.connectTimeoutSeconds`, `requestTimeoutSeconds` | HTTP timeouts |
| `api.fetchEndedAuctions` | Record sold prices from `/auctions_ended` (used by `ENDED_AUCTIONS`) |
| `api.clockSkewToleranceMs` | How far in the future a timestamp may lie before it is considered invalid |
| `valuation.*` | Valuation sources and confidence parameters ([Flip valuation](#flip-valuation)) |
| `fees.*` | Auction House cost model |
| `filters.*` | Alert filters: minimum net profit, minimum discount, minimum confidence, maximum price, exclusions, pre-existing auctions |
| `autoView.enabled` | Speed check at start-up (default `false`; `/binwatch autoview on|off` switches it while playing) |
| `autoView.autoJoinSkyblock` | While on Hypixel but outside SkyBlock, send `/play skyblock` every 15 s (`/lobby` every third try) |
| `autoView.fullReady` | Place the cursor on Buy Item Right Now, then on Confirm, for alerted flips (you still click; off by default) |
| `autoView.minGapMs` / `maxAgeSeconds` / `resultTimeoutMs` | At most one `/viewauction` per 2 s; skip flips older than 30 s; record `NO_RESPONSE` after 5 s |
| `market.enabled` | Check each flip that passed the local filters with the price service before alerting |
| `market.requireWorthIt` | Alert only flips the market check confirms (otherwise alert with a "Not worth it" line) |
| `market.alertWhenUnavailable` | Still alert, marked "No market data", when the price service cannot be reached |
| `market.minDailySales` | How often the item must sell (per day) to be worth flipping |
| `market.maxSellHours` | Reject items expected to take longer than this to sell |
| `market.undercutPercent` | Suggested list price = market price minus this percentage |
| `market.maxRequestsPerMinute` / `requestTimeoutMs` | Request budget (SkyCofl allows 100/min) and timeout |
| `market.prefilterMinNetProfit` / `prefilterMinDiscountPercent` / `prefilterMinConfidence` | Looser local bar for flips that SkyCofl then checks (never stricter than `filters`) |
| `summary.enabled` / `updateSeconds` / `topCount` | Live Discord profit summary: on/off, refresh period, flips listed |
| `discordBot.enabled` | Run the [Discord bot](#discord-bot) when `discordBotToken` is set in `secrets.json` |
| `discordBot.alertsChannelId` / `tradesChannelId` / `summaryChannelId` / `logChannelId` | Channel for each kind of message; empty falls back to the webhook or is skipped |
| `discordBot.slashCommands` | Register `/profit` and `/status` (administrators only by default; answers only visible to you) |
| `chat.alerts` | Show alerted flips in your chat with clickable [VIEW] and [COPY] (default `true`) |
| `discord.*` | Embed color, `colorByRarity`, `itemImages` and `itemImageUrl` (HTTPS, must contain `{id}`), rate limits, queue size, per-item cooldown, attempts, timeout, maximum queue age |
| `storage.*` | Data retention in days and the row cap. Pruned hourly so the database cannot grow without bound |
| `logging.*` | Verbosity, per-auction logging, log rotation |
| `export.*` | Automatic CSV export and row cap per file |

Edits to `config.json` are picked up automatically within a few seconds (same rules as `/binwatch reload`).

The webhook URL is **not** in this file. It goes in `secrets.json`.

### Auto-open and speed check

When switched on (`/binwatch autoopen on`, or `autoView.enabled` in the config), every alerted flip is opened in game with `/viewauction <uuid>`, at most one every 2 s, so the Auction House menu is already in front of you. **It never clicks anything, so it cannot buy.** You decide and buy by hand.

**Profit banner.** Above the auction menu, a banner shows what the flip is worth to you, for example:

```
BIN Watch: +13.78M profit after fees
List at 117.91M for 1h · median 122.1M · 60 sales/day
```

It appears for every alerted flip you open, automatically or with [VIEW], and stays on the Confirm Purchase screen. It is only drawn when the item in the menu has the same name as the alerted flip, so it never labels a different auction. Without market data it shows the local estimate instead.

**Full ready** (off by default; `/binwatch fullready on|off`, or `autoView.fullReady`). On an alerted flip's auction menu, the mouse cursor is placed on **Buy Item Right Now** (slot 31). After *you* click it, the cursor is placed on the green **Confirm** button of the Confirm Purchase screen (slot 11). Buying then takes two clicks without moving the mouse. It only moves the cursor: nothing is clicked and nothing is sent to Hypixel unless you click. It only acts on the auction BIN Watch alerted (the item name must match), and places each button once per flip, so it never fights your mouse.

Rules:
- A Confirm Purchase screen is never replaced: new flips wait until you have confirmed or closed it.
- Any other open screen, chat included, is replaced by the new auction, because speed matters.
- It only works inside SkyBlock. With `autoView.autoJoinSkyblock` (default `true`), when you are on Hypixel but not in SkyBlock it sends `/play skyblock` every 15 s, and `/lobby` every third try in case you are in limbo. Set it to `false` if you leave SkyBlock on purpose.
- Flips older than `autoView.maxAgeSeconds` (30) are skipped.
- Stop it any time with `/binwatch autoopen off`.

**Speed check.** Each opened flip is also recorded: was it still for sale when the menu arrived, or had someone else already bought it?

| Result | Meaning |
|---|---|
| `BUYABLE` | Still for sale: the gold-nugget "Buy Item Right Now" button |
| `CANT_AFFORD` | Still for sale, but your purse is too small (poisonous potato) |
| `PURCHASE_DELAY` | Still for sale and freshly listed: Hypixel's short delay before a new BIN can be bought (bed) |
| `SOLD` / `ENDED` / `NOT_FOUND` | Already gone: the lore says sold or ended, or Hypixel replied that the auction was not found |
| `NO_RESPONSE`, `UNKNOWN`, `COMMAND_REFUSED`, `OWN_AUCTION`, `NOT_BIN`, `SKIPPED_STALE` | Inconclusive; the raw button, lore and chat are recorded so the rules can be refined |

- Each result appears in your chat, for example `Auto-open: Hyperion -> STILL FOR SALE (BUYABLE), 1.4 s after Hypixel published`.
- Every check is appended to `binwatch/exports/autoview_checks.csv`. Each row has the timings (command and result after publish), the raw button text and lore, the item's status lines, and any chat reply.
- `/binwatch autoopen status` shows the totals and the median time to open.

**Caution:** sending commands automatically is automation, and Hypixel's rules restrict that. Use it only while you are at the game.

---

## Data, exports and reports

The database is H2 with a versioned schema (`meta.schema_version`). It is transactional, so batch inserts commit atomically. If the file cannot be opened (for example, it is corrupted), it is renamed to `binwatch.corrupt-<time>.mv.db` and a fresh database is created; the old history stays in the renamed file. First-observation rows are insert-only and are never overwritten.

`/binwatch export` writes two UTF-8 CSV files with ISO-8601 UTC timestamps:

| File | Contents |
|---|---|
| `api_observations_*.csv` | `source=API`: auction and item UUID, item, price, start/end, `first_api_observed_utc`, `api_observed_delay_ms`, snapshot `lastUpdated`, poll id, page, `api_source_status` (COMPLETE/INCONSISTENT/INCOMPLETE), `observation_kind` |
| `flip_alerts_*.csv` | Alert state (QUEUED/SENT/FAILED/DROPPED/EXPIRED), attempts, delivery time, estimated net profit |

### Reading a timing report

- **API delay (median/p90):** how long after the recorded start this monitor first saw new auctions. It includes snapshot cadence, CDN cache and polling gaps.
- The header gives the observation time range and the API poll mode.
- The report lists its own limitations.
- `/binwatch export` writes `timing_report_<time>.txt` next to the CSVs.

---

## Standalone app (no Minecraft)

A console program that runs BIN Watch's own collector, valuation, filters and SkyCofl market check without Minecraft, Fabric or a game window. It keeps **one** Discord message titled **AH Flipper** up to date with the best opportunities that are still listed. Like the mod it is read-only: it never buys, bids, or connects to the game.

### What is reused and what is not

Everything that decides what a flip is comes unchanged from the mod:
- the paginated collector with snapshot-timed polling (`api/`)
- item decoding and normalisation (`item/`, `nbt/`)
- the valuation, fees, filters and exclusions (`value/`)
- the SkyCofl check (`market/`)
- the database (`store/`)
- the alert rules in `BinWatchService`

Profit means exactly what it means in the mod: SkyCofl's net profit after fees when the market check ran, otherwise the local estimate after fees.

Only the `client/` package depends on Minecraft: chat, the auto-open, the profit banner, full ready and AH chat tracking. Those act on the game, so the standalone app does not have them. Trade tracking needs the game's chat, so the profit summary stays empty here and is off by default.

The data source is the official Hypixel API: `GET /v2/skyblock/auctions` (all pages) and `/v2/skyblock/auctions_ended`. Both are public and need no key, so **no API key is asked for**. Hypixel publishes a new snapshot about every 60 seconds, and the app cannot see a listing before Hypixel publishes it.

| Part | Where |
|---|---|
| `main`: startup, first run, lifecycle, Ctrl+C | `standalone/StandaloneMain.java`, `standalone/FirstRunSetup.java` |
| `data_source`: requests, pagination, retries, caching | `api/AuctionApiClient.java`, `api/AuctionCollector.java` (the unchanged engine) |
| `flipper`: detection and profit | `value/`, `market/`, `service/BinWatchService.java` (unchanged rules) |
| `tracker`: current opportunities, dedupe, ranking | `standalone/FlipBoard.java` |
| `discord_publisher`: embed, create and edit, 429 | `standalone/BoardEmbed.java`, `standalone/BoardPublisher.java` |
| `metrics` | `standalone/Metrics.java` |
| `config` | `config/BinWatchConfig.java` (new `board` section), `config/Secrets.java` |

### Install and launch

You need Java 25 (check with `java -version`). Build the jar once:

```bash
./gradlew standaloneJar
```

It is written to `build/libs/binwatch-standalone-1.0.0.jar`. The jar is about 3.4 MB and holds the engine plus Gson and H2. Run it:

```bash
java -jar build/libs/binwatch-standalone-1.0.0.jar
```

On Windows you can double-click `run-standalone.bat`, which builds the jar if needed and starts it. `./run-standalone.sh` does the same on Linux and macOS.

Stop it with **Ctrl+C**. The Discord message then switches to "Stopped", so nobody mistakes it for live data.

| Option | Effect |
|---|---|
| `--dry-run` | Scans and builds the embed, but sends nothing to Discord (no board, no alerts, no bot) |
| `--setup` | Enter the webhook and the main thresholds again |
| `--dir <path>` | Where config, data and logs live (default `./binwatch-data`) |
| `--verbose` | Also print the detailed log lines |
| `--run-for <s>` | Stop after that many seconds (used for benchmarks) |

### First run

1. It creates `binwatch-data/config/config.json` with standalone defaults. The format is the same as the mod's; see `config.standalone.example.json`.
2. It asks for the Discord webhook URL, with the input hidden. It checks the webhook with Discord using a `GET` of the webhook, which posts nothing, and shows you the webhook's name.
3. It saves the webhook to `binwatch-data/config/secrets.json`, readable only by your user (an owner-only ACL on Windows, `chmod 600` elsewhere). The `BINWATCH_DISCORD_WEBHOOK_URL` environment variable overrides it.
4. It asks for an optional second webhook for the [profit tracker](#profit-tracker). Use a different channel. Press Enter to skip it.
5. It asks for the minimum profit, the minimum discount and how many flips to show. Press Enter to keep each default.
6. It starts scanning.

`--setup` asks the same questions again. Pressing Enter at a webhook question keeps the current one.

The webhook URL is never printed or logged. Without a terminal (for example when run as a service), set the environment variable or the secrets file first. Otherwise it stops with a clear message.

### Configuration

All of the mod's settings apply: `filters.*`, `fees.*`, `valuation.*`, `market.*`, `api.*` and `logging.level`. Changes to `config.json` apply within 3 seconds. The main ones:

| Setting | Default | Meaning |
|---|---|---|
| `filters.minNetProfit` | 1M | Minimum profit after fees |
| `filters.minDiscountPercent` | 10 | Minimum price below the estimated resale, in % |
| `filters.minConfidence` | 0.6 | Minimum valuation confidence |
| `filters.excludedItemIds`, `excludedNameKeywords`, `maxPurchasePrice` | none | Exclusions |
| `market.enabled` | true | SkyCofl check before a flip counts (see [Market check](#market-check-and-listing-advice)) |
| `board.maxFlips` | 8 | Flips shown in the message (1 to 20). The rest stay tracked |
| `board.minProfitPercent` | 0 | Extra filter for the board: profit as % of the buy price |
| `board.minEditIntervalMs` | 1500 | Minimum gap between edits. Changes in between become one edit |
| `board.maxTracked` | 200 | Opportunities kept in memory |
| `discord.enabled` | false | Also send the mod's one-message-per-flip alerts to the same webhook |
| `profit.enabled` | true | Run the [profit tracker](#profit-tracker) |
| `profit.postEachResult` | true | Post one short message per flip result, as well as the summary |
| `profit.maxFollowMinutes` | 60 | A flip still listed this long after its alert counts as buyable, and is no longer followed |
| `profit.topCount` | 5 | Best buyable flips of the last 24 h listed in the summary |
| `profit.keepDays` | 30 | How long results are kept for the list (the all-time totals are kept forever) |
| `logging.level` | INFO | Detail of `binwatch-data/logs/binwatch.log` |

### The Discord message

There is one message. It is created on the first run and edited after that. Its id is stored in the database together with a hash of the webhook:
- a restart edits the same message
- a new webhook starts a new message
- if someone deletes the message, the next update posts a new one

What the message shows:
- The title **AH Flipper**, the scanner status (Starting, Scanning, Data problem or Stopped), the age of the latest snapshot, and the number of opportunities tracked.
- For each flip:
  - the item, buy price and resale
  - the profit after fees, with the percentage of the buy price
  - whether SkyCofl checked it, and the suggested listing time
  - when it was seen and when the auction ends
  - `/viewauction <uuid>` to open it in game. No web link is made up for an auction; the command is the reliable way to open it.
- Ages use Discord's relative timestamps, which every viewer's client keeps current. The message does not need edits just to update "x seconds ago".
- With no opportunities it says so, instead of showing old results.

Updates are coalesced. A change only asks for a publish, and one worker sends the newest board when it is allowed to. Any number of changes during a slow or rate-limited request become a single edit, and nothing is sent when nothing visible changed. Scanning never waits for Discord.

How failures are handled:
- HTTP 429: it waits the time Discord asks for.
- Discord reports the webhook's bucket as empty: it waits for the reset instead of causing a 429.
- Other failures: it backs off from 2 s up to 60 s.
- A rejected webhook (401, 403, 404): it retries every 5 minutes.

### Which flips are on the board

A flip joins the board when it passes the same filters and market check that make the mod alert it. It leaves when either:
- a **complete** snapshot no longer lists the auction (bought, cancelled or expired), or
- its end time passes.

A partial snapshot cannot prove an auction is gone, so it removes nothing. The same auction is never listed twice.

Auctions that already existed when the app started are not opportunities (the mod's `alertOnPreexisting` rule), so a restart never floods the board. The board is saved to `binwatch-data/data/board.json`. After a restart, saved entries are shown as "not re-checked yet" until the first complete snapshot confirms or removes them.

### Profit tracker

The profit tracker answers one question: when the alert reached Discord, could you still have bought the flip? It gives the profit you could have made, and shows how long each flip stayed buyable.

- It follows every flip shown in the board message, from the moment Discord confirmed that message.
- Hypixel's `auctions_ended` lists every sale with its exact time, about a minute after it happens. The app already downloads it once a minute.

How each flip is decided:

| Result | When | Counted as profit you could have made? |
|---|---|---|
| ✅ Could have bought | It sold after the alert reached Discord ("sold 14.2 s after the alert") | Yes |
| ✅ Could have bought | It was still unsold `profit.maxFollowMinutes` after the alert | Yes |
| ✅ Could have bought | It disappeared with no recorded sale (probably cancelled), but a snapshot taken after the alert still listed it | Yes |
| ❌ Sniped | It sold before the alert reached Discord ("sold 1.3 s before the alert reached Discord") | No |
| ❔ No result | It disappeared with no recorded sale, and was never seen listed after the alert | No |

- The alert time is converted to Hypixel's clock with the collector's measured offset, so it can be compared with Hypixel's sale times.
- A result is known one to two minutes after the alert, because that's when Hypixel publishes the sale. A flip that vanishes from the auctions is given 3 more minutes for its sale to appear.
- The profit is the estimated profit after fees shown in the alert: what you could have made, not money you made.

What goes to the profit webhook (`discordProfitWebhookUrl` in `secrets.json`):
- **One summary message**, edited in place like the board:
  - potential profit for the last hour, the last 24 h and all time, with the counts of buyable, sniped and unknown flips
  - how fast the buyable flips sold (median and fastest time after the alert, and how many sold within 5 s and 30 s)
  - the best flips you could have bought in the last 24 h
- **One short message per flip** once its result is known, with the item, the profit, `/viewauction`, and how long after the alert it sold. Turn these off with `profit.postEachResult`.

Without a profit webhook, the results are printed in the console (`[PROFIT] …`). The state is saved to `binwatch-data/data/profit.json`, so a restart keeps following the flips that are still pending, and keeps the all-time totals.

### Console output and timings

```
22:05:15 [FLIPS] New: Anita's Artifact | buy 10M -> resale 12.4M | profit 2M (20.0%) | SkyCofl checked | 247 ms after its page arrived
22:05:17 [SCAN] Snapshot 22:05:06 COMPLETE: 44,719 auctions, 45/45 pages, 93 new listings, snapshot 8.1 s old when detected, page 0 received 328 ms (+/-55) after Hypixel published it
22:05:17 [PROCESS] Valued 153 listing(s) in 32.9 ms CPU; all pages fetched and processed 2232 ms after page 0
22:05:17 [METRICS] Board 1 | Candidates 153 | Discord 118 ms | API errors 0 | API retries 0 | Rate limits 0
```

Four moments are kept apart:

| Moment | Shown as |
|---|---|
| Hypixel's snapshot time (`lastUpdated`) | "Snapshot 22:05:06" |
| When Hypixel actually started serving it | "Hypixel published it". This is bracketed between the last probe that still got the old snapshot and the first that got the new one |
| When the app received the page | "page 0 received … after Hypixel published it". "Snapshot … old when detected" is measured from `lastUpdated` |
| When processing finished, and when Discord confirmed the edit | `[FLIPS] … ms after its page arrived` and `[DISCORD] Embed updated in … ms (… ms after the page with the change was received)` |

Hypixel stamps `lastUpdated` several seconds before it publishes, so "snapshot 8 s old when detected" is mostly Hypixel's own delay. The figure the app controls is the time from publication. Durations inside the app use a monotonic clock. Ctrl+C prints the median, p95 and maximum of every measurement.

### Measured performance (2026-10-30)

**Live, dry run:** `--dry-run --run-for 600` against the real Hypixel API, from 22:11 to 22:21 local time, on a Windows 11 desktop over a home connection, with Java 25. Snapshot-timed polling and 8 parallel page requests (the defaults). Each snapshot was about 44,600 auctions on 45 pages. SkyCofl was on.

| Measurement | Median | p95 / max | Samples |
|---|---|---|---|
| Hypixel published the snapshot → page 0 received | 581 ms | 741 ms | 10 |
| Snapshot `lastUpdated` → page 0 received (mostly Hypixel's own publish delay) | 8.6 s | 17.0 s (the first poll after start-up) | 11 |
| Page 0 → all 45 pages fetched and processed | 2.2 s | 4.7 s | 11 |
| Valuation CPU per snapshot (about 112 new listings) | 6.0 ms | 35 ms | 11 |
| Valuation CPU for the start-up baseline (41,953 listings) | 35 ms | | 1 |
| Page received → flip on the board, including the SkyCofl check | 141 ms | 3,044 ms | 4 |
| Building the embed | 0.36 ms | 2.7 ms | 17 |

- Result: 11 snapshots (9 complete, 2 inconsistent, 0 incomplete) and 0 API errors, retries or rate limits.
- Real flips put on the board:
  - an Elephant pet, +5.7M
  - a Kingfisher Dye, +2.4M
  - a Hydromax, +1.2M
  - a Hooverius, +1.5M
- 3 of them sold within a minute, and were removed at the next complete snapshot.
- The 3,044 ms case was one SkyCofl request that hit its 3 s timeout (`market.requestTimeoutMs`). That flip was then judged on the local estimate.
- New listings are valued as page 0 streams in, so a flip reaches the board before the remaining pages finish downloading.

Discord timings are not in this run: the dry run sends nothing, and the real webhook was not used for testing. A normal run prints `[DISCORD] Embed updated in … ms` for every edit, and the Ctrl+C summary includes the request time and the time from the page arriving to Discord's confirmation. For reference, the mod's own alert POSTs to Discord took about 300 ms.

**Offline (unit test `processesARepresentativeSnapshotQuickly`):** scripted pages with 20,000 listings over 20 pages and 400 item types, on the same machine.

| Measurement | Time |
|---|---|
| Baseline snapshot of 20,000 listings: parse, decode, store and value | 1.45 to 1.96 s |
| Next snapshot of 20,300 listings: parse and store everything, value the 300 new ones | 169 to 197 ms, of which valuation CPU was 26 to 36 ms |

---

## Building and testing from source

Requirements: JDK 25 on `PATH` or `JAVA_HOME`. Gradle is provided by the wrapper.

```bash
./gradlew build
```

The JAR is written to `build/libs/binwatch-1.0.0.jar`. The `-sources.jar` next to it is not needed to play.

```bash
./gradlew test
```

Runs 104 offline tests. They use fake HTTP transports, in-memory or temporary databases, and a controllable clock.

```bash
./gradlew test --tests dev.binwatch.LiveApiTest -Dbinwatch.liveTest=true
```

Optional. Read-only GET requests to the two public endpoints. Never run by default.

```bash
./gradlew runClient
```

Starts a 26.2 development client with the mod.

The tests cover:
- pagination, and inconsistent and incomplete snapshots,
- timeouts, retries, malformed responses, 429/`Retry-After`, 404/503,
- UUID deduplication,
- persistence and first-seen preservation across restarts,
- delay calculation and formatting (seconds, minutes, hours, days),
- missing, invalid and future timestamps,
- snapshot-timed polling: probe bursts, publish-time bracketing, stale pages, re-sync and error backoff,
- invalid and low-confidence valuations,
- profit and fee maths,
- Discord embed fields and payload validity,
- Discord errors and 429 handling,
- cooldowns and deduplication across restarts,
- config validation,
- secret redaction in validation messages, chat and log files,
- CSV quoting,
- NBT decoding,
- recovery from a corrupted database,
- synthetic test mode,
- SkyCofl reply parsing and 429 pause, worth-it check and listing advice,
- AH chat parsing and the trade ledger from purchase to collection,
- the profit summary, posted once and then edited in place (and re-posted if deleted),
- the auction-screen profit banner text and `/viewauction` matching,
- the Discord bot: channel routing with the bot token, summary editing in a bot channel, the background feed, token validation and redaction,
- the standalone app: board add, change and remove, dedupe, ranking, the % and size caps; removal only on complete snapshots; restart recovery; embed limits, empty state and coin format; post once then edit, unchanged skips, re-posting a deleted message, 429 waits, back-off on a rejected webhook; 200 changes during a slow Discord request becoming one edit without blocking the scan; dry run never calling Discord; first-run webhook check (GET only, never echoed); invalid config and missing webhook; the whole pipeline from scripted Hypixel pages to Discord, including a restart that edits the same message; and a 20,000-listing snapshot processing benchmark,
- the profit tracker: buyable with its seconds after the alert, sniped before the alert, a sale seen before the alert was confirmed, vanished auctions after the grace period, still listed past the follow time, one follow per auction, state across a restart, and the whole path from an `auctions_ended` sale to the profit webhook.

```bash
./gradlew standaloneJar
```

Builds the standalone app (see [Standalone app](#standalone-app-no-minecraft)). `./gradlew build` builds both jars.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Game says the mod needs a different Minecraft version | Use the **26.2** Fabric profile, with Fabric API `0.161.0+26.2` and Java 25 |
| `API: Running (last error: …)` | `/binwatch diag` shows the retry and 429 counters. Transient errors recover on the next poll |
| `Last snapshot: … INCOMPLETE` | Some pages failed after retries. The next poll fetches everything again |
| No flips ever pass | Valuations need one complete snapshot first, and sales history builds up over time. Check `/binwatch top` for the reject reasons. Strict keys mean rare variants get no valuation, which is intended |
| `/binwatch testwebhook` says `PERMANENT_ERROR HTTP 401/404` | The webhook was deleted or the token is wrong. Copy it again into `secrets.json`, then run `/binwatch reload` |
| `Discord webhook URL rejected` | It must be `https://discord.com/api/webhooks/<id>/<token>` |
| Database errors at start | Look in `.minecraft/binwatch/` for a `binwatch.corrupt-*.mv.db`. A fresh database was created automatically |
| Standalone: "No Discord webhook configured" | Run it in a terminal to be asked, or set `BINWATCH_DISCORD_WEBHOOK_URL`, or run `--setup` |
| Standalone: "Discord rejected the board message: HTTP 401/403/404" | The webhook was deleted or its channel is gone. Run with `--setup` and paste a new one. It retries every 5 minutes |
| Standalone: board stays empty | Normal for a while: only new listings that clear the filters and SkyCofl appear, and good ones sell within seconds. Try `--dry-run --verbose`, or lower `filters.minNetProfit` |
| Standalone: the profit tracker shows nothing | Results take 1–2 minutes after an alert. Check that `[PROFIT] Profit tracker on: results go to the profit webhook` is printed at start-up; if it isn't, add the webhook with `--setup` |
| Standalone: `UnsupportedClassVersionError` | It needs Java 25 (`java -version`) |
| Standalone: `?` instead of symbols in the console | The console uses ASCII stand-ins for some symbols; Discord shows the real ones. Unusual item-name characters may still show as `?` |
| Large disk use | Lower `storage.retentionDays` or `storage.maxApiObservationRows`. Pruning runs hourly |

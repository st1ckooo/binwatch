package dev.binwatch.market;

import dev.binwatch.api.HttpTransport;
import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.log.BwLog;
import dev.binwatch.value.FeeModel;
import dev.binwatch.value.FlipCandidate;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs market checks off the collector thread: each flip that passed the local filters is priced by the provider and
 * handed on with the verdict attached. Several flips can arrive in the same snapshot; six workers check them in
 * parallel so none waits behind another, while the provider's own budget keeps the request rate within limits.
 */
public final class MarketService {
	private final HttpTransport http;
	private final Clock clock;
	private final Supplier<BinWatchConfig> config;
	private final ExecutorService workers = Executors.newFixedThreadPool(6, r -> {
		Thread t = new Thread(r, "BINWatch-market");
		t.setDaemon(true);
		return t;
	});
	private PriceProvider provider;
	private BinWatchConfig.Market providerConfig;

	public final AtomicLong checked = new AtomicLong();
	public final AtomicLong worthIt = new AtomicLong();
	public final AtomicLong rejected = new AtomicLong();
	public final AtomicLong unavailable = new AtomicLong();
	private volatile String lastError;

	public MarketService(HttpTransport http, Clock clock, Supplier<BinWatchConfig> config) {
		this.http = http;
		this.clock = clock;
		this.config = config;
	}

	/** Test hook: use a given provider instead of building one from the config. */
	public synchronized void setProvider(PriceProvider p) {
		this.provider = p;
		this.providerConfig = config.get().market;
	}

	private synchronized PriceProvider provider() {
		BinWatchConfig.Market m = config.get().market;
		if (provider == null || providerConfig != m) {
			provider = new CoflProvider(http, m.baseUrl, clock, m.maxRequestsPerMinute, Duration.ofMillis(m.requestTimeoutMs));
			providerConfig = m;
		}
		return provider;
	}

	public String lastError() {
		return lastError;
	}

	/** Prices {@code c} asynchronously and passes it (with the check attached) to {@code then}. */
	public void submit(FlipCandidate c, String itemBytes, Consumer<FlipCandidate> then) {
		workers.execute(() -> then.accept(check(c, itemBytes)));
	}

	/** Prices {@code c} on the calling thread. */
	public FlipCandidate check(FlipCandidate c, String itemBytes) {
		BinWatchConfig cfg = config.get();
		PriceProvider p = provider();
		long started = clock.millis();
		MarketCheck m;
		try {
			MarketQuote q = p.quote(itemBytes);
			m = MarketCheck.evaluate(q, c.auctionUuid(), c.price(), new FeeModel(cfg.fees), cfg.market,
					cfg.filters.minNetProfit, p.attribution());
			checked.incrementAndGet();
			(m.worthIt() ? worthIt : rejected).incrementAndGet();
			lastError = null;
			BwLog.info(String.format(Locale.ROOT, "Market check (%s, %d ms) %s %s: median %d, lowest BIN %d, %.1f sales/day"
					+ " -> list at %d for %d h, net %d, %s", p.name(), clock.millis() - started, c.auctionUuid(),
					c.itemName(), q.median(), q.lowestBin(), q.dailySales(), m.listPrice(), m.listHours(), m.netProfit(),
					m.worthIt() ? "worth it" : "not worth it (" + String.join("; ", m.reasons()) + ")"));
		} catch (MarketException e) {
			unavailable.incrementAndGet();
			lastError = e.getMessage();
			m = MarketCheck.unavailable(e.getMessage(), p.name(), p.attribution());
			BwLog.throttled(BwLog.Level.WARN, "market-check", "Market check unavailable for " + c.itemName() + ": "
					+ e.getMessage(), null);
		} catch (RuntimeException e) {
			unavailable.incrementAndGet();
			lastError = e.toString();
			m = MarketCheck.unavailable("unexpected error", p.name(), p.attribution());
			BwLog.throttled(BwLog.Level.ERROR, "market-check-bug", "Market check failed", e);
		}
		return c.withMarket(m);
	}

	public void shutdown() {
		workers.shutdownNow();
	}
}

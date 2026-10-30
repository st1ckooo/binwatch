package dev.binwatch.trade;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.log.BwLog;
import dev.binwatch.store.ObservationStore;
import dev.binwatch.store.Records.Trade;
import dev.binwatch.store.Records.TradeStatus;
import dev.binwatch.util.TimeFmt;
import dev.binwatch.value.FeeModel;
import dev.binwatch.value.FlipCandidate;

import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Turns your own Auction House chat messages into flip records: a purchase opens a trade (linked to the BIN Watch
 * alert for that listing when there was one), listing, selling and collecting move it along, and profit is worked out
 * with the configured fee model. Read-only with respect to the game: it only reads chat the server sent you.
 */
public final class TradeLedger {
	/** How long an alert can be matched to a purchase. */
	static final long ALERT_MATCH_MS = 30 * 60_000L;

	private final ObservationStore store;
	private final Clock clock;
	private final Supplier<BinWatchConfig> config;
	private final Deque<FlipCandidate> recentAlerts = new ArrayDeque<>();
	private volatile Runnable onChange = () -> {
	};
	private volatile BiConsumer<TradeChatParser.Event, List<String>> eventListener = (e, lines) -> {
	};

	public TradeLedger(ObservationStore store, Clock clock, Supplier<BinWatchConfig> config) {
		this.store = store;
		this.clock = clock;
		this.config = config;
	}

	public void setOnChange(Runnable r) {
		this.onChange = r == null ? () -> {
		} : r;
	}

	/** Receives each recognised trade message and the lines shown to the player (for the Discord trades channel). */
	public void setEventListener(BiConsumer<TradeChatParser.Event, List<String>> l) {
		this.eventListener = l == null ? (e, lines) -> {
		} : l;
	}

	/** Remembers an alerted flip so a purchase of it can be linked to its advice. */
	public synchronized void rememberAlert(FlipCandidate c) {
		recentAlerts.addLast(c);
		long cutoff = clock.millis() - ALERT_MATCH_MS;
		while (!recentAlerts.isEmpty() && recentAlerts.peekFirst().firstApiObservedMs() < cutoff) {
			recentAlerts.pollFirst();
		}
		while (recentAlerts.size() > 500) {
			recentAlerts.pollFirst();
		}
	}

	/**
	 * Handles one chat line. Returns lines to show the player (empty when the line is not a trade message).
	 */
	public synchronized List<String> onChat(String line) {
		TradeChatParser.Event e = TradeChatParser.parse(line);
		if (e == null) {
			if (TradeChatParser.looksAuctionRelated(line)) {
				BwLog.info("Auction chat line not recognised (not tracked): " + line);
			}
			return List.of();
		}
		try {
			List<String> out = switch (e) {
				case TradeChatParser.Purchased p -> purchased(p);
				case TradeChatParser.Listed l -> listed(l);
				case TradeChatParser.Sold s -> sold(s);
				case TradeChatParser.Collected c -> collected(c);
				case TradeChatParser.Cancelled c -> cancelled(c);
			};
			onChange.run();
			try {
				eventListener.accept(e, out);
			} catch (RuntimeException ex) {
				BwLog.throttled(BwLog.Level.WARN, "trade-event", "Trade event listener failed: " + ex, null);
			}
			return out;
		} catch (SQLException ex) {
			BwLog.throttled(BwLog.Level.ERROR, "trade-db", "Could not record trade from chat", ex);
			return List.of("Could not record this trade (database error); see binwatch.log");
		}
	}

	private List<String> purchased(TradeChatParser.Purchased p) throws SQLException {
		String key = TradeChatParser.nameKey(p.item());
		FlipCandidate alert = matchAlert(key, p.price());
		long now = clock.millis();
		Long listPrice = null;
		Integer listHours = null;
		Long net = null;
		if (alert != null) {
			listPrice = alert.effectiveResale();
			net = alert.effectiveNetProfit();
			listHours = alert.market() != null && alert.market().available() ? alert.market().listHours() : null;
		}
		Trade t = store.insertTrade(new Trade(null, p.item(), key, alert == null ? null : alert.auctionUuid(), p.price(), now,
				listPrice, listHours, net, null, null, null, null, null, null, null, TradeStatus.BOUGHT));
		BwLog.info("Trade " + t.id() + ": bought " + p.item() + " for " + p.price()
				+ (alert == null ? " (no matching alert)" : " from alert " + alert.auctionUuid()));
		if (alert == null) {
			return List.of("Tracking purchase: " + p.item() + " for " + TimeFmt.shortCoins(p.price())
					+ " (not from a BIN Watch alert, so no listing advice)");
		}
		return List.of("Bought " + p.item() + " for " + TimeFmt.shortCoins(p.price()) + ". List at "
				+ TimeFmt.coins(listPrice) + (listHours == null ? "" : " for " + listHours + "h") + " -> est. "
				+ signed(net) + " after fees");
	}

	private List<String> listed(TradeChatParser.Listed l) throws SQLException {
		Trade t = oldest(TradeChatParser.nameKey(l.item()), EnumSet.of(TradeStatus.BOUGHT));
		if (t == null) {
			return List.of();
		}
		store.updateTrade(with(t, TradeStatus.LISTED, clock.millis(), t.soldPrice(), t.buyer(), t.soldMs(),
				t.collectedCoins(), t.collectedMs(), t.profit()));
		return List.of();
	}

	private List<String> sold(TradeChatParser.Sold s) throws SQLException {
		String key = TradeChatParser.nameKey(s.item());
		long now = clock.millis();
		Trade t = oldest(key, EnumSet.of(TradeStatus.LISTED, TradeStatus.BOUGHT));
		FeeModel fees = new FeeModel(config.get().fees);
		if (t == null) {
			store.insertTrade(new Trade(null, s.item(), key, null, null, null, null, null, null, null, s.price(), s.buyer(),
					now, null, null, null, TradeStatus.UNTRACKED));
			return List.of("Sold " + s.item() + " for " + TimeFmt.shortCoins(s.price())
					+ " (purchase not tracked, so no profit figure)");
		}
		long profit = s.price() - fees.totalCosts(s.price()) - t.boughtPrice();
		store.updateTrade(with(t, TradeStatus.SOLD, t.listedMs(), s.price(), s.buyer(), now, null, null, profit));
		BwLog.info("Trade " + t.id() + ": sold " + s.item() + " for " + s.price() + ", profit " + profit);
		return List.of("Sold " + s.item() + " for " + TimeFmt.shortCoins(s.price()) + " -> profit " + signed(profit)
				+ " after fees (held " + TimeFmt.duration(Math.max(0, now - t.boughtMs())) + ")");
	}

	private List<String> collected(TradeChatParser.Collected c) throws SQLException {
		String key = TradeChatParser.nameKey(c.item());
		long now = clock.millis();
		Trade t = oldest(key, EnumSet.of(TradeStatus.SOLD));
		if (t == null) {
			// Sold while BIN Watch was not running: the collection is the first we hear of the sale.
			t = oldest(key, EnumSet.of(TradeStatus.LISTED, TradeStatus.BOUGHT));
		}
		FeeModel fees = new FeeModel(config.get().fees);
		if (t == null) {
			store.insertTrade(new Trade(null, c.item(), key, null, null, null, null, null, null, null, null, c.buyer(), null,
					c.coins(), now, null, TradeStatus.UNTRACKED));
			return List.of();
		}
		// Collected coins already have the claim tax taken off; the listing fee was paid when listing.
		long gross = t.soldPrice() != null ? t.soldPrice() : c.coins();
		long profit = c.coins() - fees.listingFee(gross) - config.get().fees.extraCostPerFlip - t.boughtPrice();
		store.updateTrade(with(t, TradeStatus.COLLECTED, t.listedMs(), t.soldPrice() != null ? t.soldPrice() : c.coins(),
				t.buyer() != null ? t.buyer() : c.buyer(), t.soldMs() != null ? t.soldMs() : now, c.coins(), now, profit));
		BwLog.info("Trade " + t.id() + ": collected " + c.coins() + " for " + c.item() + ", profit " + profit);
		return List.of("Collected " + TimeFmt.shortCoins(c.coins()) + " for " + c.item() + " -> profit " + signed(profit));
	}

	private List<String> cancelled(TradeChatParser.Cancelled c) throws SQLException {
		Trade t = oldest(TradeChatParser.nameKey(c.item()), EnumSet.of(TradeStatus.LISTED));
		if (t != null) {
			store.updateTrade(with(t, TradeStatus.BOUGHT, null, null, null, null, null, null, null));
		}
		return List.of();
	}

	/** Alert for the same item; one at exactly the purchase price wins, otherwise the most recent. */
	private FlipCandidate matchAlert(String key, long price) {
		FlipCandidate byName = null;
		for (Iterator<FlipCandidate> it = recentAlerts.descendingIterator(); it.hasNext(); ) {
			FlipCandidate c = it.next();
			if (!TradeChatParser.nameKey(c.itemName()).equals(key)) {
				continue;
			}
			if (c.price() == price) {
				return c;
			}
			if (byName == null) {
				byName = c;
			}
		}
		return byName;
	}

	private Trade oldest(String key, EnumSet<TradeStatus> states) throws SQLException {
		List<Trade> ts = store.tradesByName(key, states);
		return ts.isEmpty() ? null : ts.get(0);
	}

	private static Trade with(Trade t, TradeStatus status, Long listedMs, Long soldPrice, String buyer, Long soldMs,
			Long collectedCoins, Long collectedMs, Long profit) {
		return new Trade(t.id(), t.itemName(), t.nameKey(), t.auctionUuid(), t.boughtPrice(), t.boughtMs(),
				t.expectedListPrice(), t.expectedListHours(), t.expectedNet(), listedMs, soldPrice, buyer, soldMs,
				collectedCoins, collectedMs, profit, status);
	}

	static String signed(Long coins) {
		if (coins == null) {
			return "?";
		}
		return (coins >= 0 ? "+" : "-") + TimeFmt.shortCoins(Math.abs(coins));
	}

	/** All trades, for the summary. */
	public List<Trade> trades() throws SQLException {
		return new ArrayList<>(store.allTrades());
	}
}

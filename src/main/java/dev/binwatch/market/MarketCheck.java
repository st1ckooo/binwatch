package dev.binwatch.market;

import dev.binwatch.config.BinWatchConfig;
import dev.binwatch.value.FeeModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Worth-it verdict and listing advice for one flip, from a market quote.
 *
 * @param quote       the market data, null when the service could not be asked or answered
 * @param error       why there is no quote (null when there is one)
 * @param listPrice   suggested BIN price: the market price (median, or the cheapest other BIN if lower), undercut
 * @param fees        listing fee, claim tax and extra cost at that price
 * @param netProfit   listPrice - fees - purchase price
 * @param listHours   suggested listing duration (1, 6, 12, 24 or 48 h) from how often the item sells
 * @param sellHours   expected hours until it sells (24 / sales per day), null when the item has no sales data
 * @param worthIt     passes the profit, demand and sell-time thresholds
 * @param reasons     why it is not worth it (empty when it is)
 */
public record MarketCheck(MarketQuote quote, String error, long listPrice, long fees, long netProfit, int listHours,
		Double sellHours, boolean worthIt, List<String> reasons, String source, String attribution) {

	static final int[] DURATIONS_H = {1, 6, 12, 24, 48};

	public boolean available() {
		return quote != null;
	}

	public static MarketCheck unavailable(String error, String source, String attribution) {
		return new MarketCheck(null, error, 0, 0, 0, 0, null, false, List.of(), source, attribution);
	}

	public static MarketCheck evaluate(MarketQuote q, UUID auctionUuid, long price, FeeModel fees,
			BinWatchConfig.Market cfg, long minNetProfit, String attribution) {
		long lbin = q.lowestBin();
		String self = auctionUuid.toString().replace("-", "");
		if (q.lowestBinAuctionPrefix() != null && self.startsWith(q.lowestBinAuctionPrefix())) {
			lbin = 0; // the cheapest listing is this very auction; the market price is what others ask
		}
		long base = q.median() > 0 ? q.median() : lbin;
		if (lbin > 0 && lbin < base) {
			base = lbin;
		}
		long listPrice = (long) Math.floor(base * (1 - cfg.undercutPercent / 100.0));
		long cost = fees.totalCosts(listPrice);
		long net = listPrice - cost - price;
		Double sellHours = q.dailySales() > 0 ? 24.0 / q.dailySales() : null;
		int listHours = DURATIONS_H[DURATIONS_H.length - 1];
		if (sellHours != null) {
			for (int h : DURATIONS_H) {
				if (h >= 2 * sellHours) {
					listHours = h;
					break;
				}
			}
		}
		List<String> reasons = new ArrayList<>();
		if (net < minNetProfit) {
			reasons.add("market profit " + net + " below minimum " + minNetProfit);
		}
		if (q.dailySales() < cfg.minDailySales) {
			reasons.add(String.format(Locale.ROOT, "sells only %.1f/day (minimum %.1f)", q.dailySales(), cfg.minDailySales));
		}
		if (sellHours == null || sellHours > cfg.maxSellHours) {
			reasons.add(sellHours == null ? "no recent sales" : String.format(Locale.ROOT,
					"expected to take %.0f h to sell (maximum %.0f h)", sellHours, cfg.maxSellHours));
		}
		return new MarketCheck(q, null, listPrice, cost, net, listHours, sellHours, reasons.isEmpty(), List.copyOf(reasons),
				q.source(), attribution);
	}
}

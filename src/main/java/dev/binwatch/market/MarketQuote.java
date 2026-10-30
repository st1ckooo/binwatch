package dev.binwatch.market;

/**
 * A price service's view of one exact item.
 *
 * @param lowestBin              cheapest current BIN of the same kind (0 when unknown)
 * @param lowestBinAuctionPrefix leading characters of that auction's id (without dashes), to recognise when the
 *                               cheapest listing is the candidate itself; null when unknown
 * @param median                 median recent sale price (0 when unknown)
 * @param dailySales             recent sales per day (the service's volume figure)
 * @param fastSell               price that sells quickly (0 when unknown)
 * @param itemKey                the service's grouping key for the item, for the record
 * @param source                 provider name, shown with attribution
 */
public record MarketQuote(long lowestBin, String lowestBinAuctionPrefix, long median, double dailySales, long fastSell,
		String itemKey, long fetchedAtMs, String source) {
}

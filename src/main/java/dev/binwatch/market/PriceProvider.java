package dev.binwatch.market;

/** Values one exact item. Implementations are swappable (SkyCofl today; another market API later). */
public interface PriceProvider {
	/**
	 * @param itemBytes the auction's {@code item_bytes}: base64, gzipped NBT exactly as the Hypixel API returns it
	 */
	MarketQuote quote(String itemBytes) throws MarketException;

	/** Short name shown in alerts and logs. */
	String name();

	/** Credit line the provider asks for wherever its data is shown. */
	String attribution();
}

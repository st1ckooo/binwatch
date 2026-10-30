package dev.binwatch.market;

/** The price service could not answer (network, rate limit, malformed reply, no data for the item). */
public final class MarketException extends Exception {
	public MarketException(String message) {
		super(message);
	}

	public MarketException(String message, Throwable cause) {
		super(message, cause);
	}
}

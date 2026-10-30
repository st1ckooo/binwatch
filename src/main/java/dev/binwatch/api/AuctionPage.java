package dev.binwatch.api;

import java.util.List;

/**
 * One successfully retrieved and parsed page.
 *
 * @param receivedAtMs local UTC time at which the response body was fully received
 * @param cdnAgeSeconds value of the HTTP {@code Age} header (seconds the response sat in a CDN cache), if present
 * @param serverDateMs  value of the HTTP {@code Date} header, if present (1 s resolution)
 * @param skipped       number of auction entries that were malformed and ignored
 * @param lastModified  raw HTTP {@code Last-Modified} header, if present; sent back as {@code If-Modified-Since}
 */
public record AuctionPage(
		int page,
		int totalPages,
		int totalAuctions,
		long lastUpdatedMs,
		List<ApiAuction> auctions,
		long receivedAtMs,
		Integer cdnAgeSeconds,
		Long serverDateMs,
		int skipped,
		String lastModified) {
}

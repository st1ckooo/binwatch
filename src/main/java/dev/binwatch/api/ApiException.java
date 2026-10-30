package dev.binwatch.api;

/** Failure while talking to the public Hypixel API. */
public final class ApiException extends Exception {
	public enum Kind {
		TIMEOUT, IO, HTTP, RATE_LIMITED, NOT_POPULATED, MISSING_PAGE, MALFORMED, API_ERROR, INTERRUPTED
	}

	private final Kind kind;
	private final int httpStatus;

	public ApiException(Kind kind, int httpStatus, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
		this.httpStatus = httpStatus;
	}

	public ApiException(Kind kind, String message) {
		this(kind, 0, message, null);
	}

	public Kind kind() {
		return kind;
	}

	public int httpStatus() {
		return httpStatus;
	}

	/** Whether repeating the same request later may succeed. */
	public boolean retryable() {
		return switch (kind) {
			case TIMEOUT, IO, RATE_LIMITED, NOT_POPULATED, MALFORMED -> true;
			case HTTP -> httpStatus >= 500 || httpStatus == 408;
			case MISSING_PAGE, API_ERROR, INTERRUPTED -> false;
		};
	}
}

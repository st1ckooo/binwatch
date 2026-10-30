package dev.binwatch.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small HTTP abstraction so the collectors and the Discord client can be tested against scripted fakes. */
public interface HttpTransport {
	/** @param headersAtMs local time the response headers arrived (0 when the transport does not record it) */
	record Response(int status, Map<String, List<String>> headers, String body, long headersAtMs) {
		public Response(int status, Map<String, List<String>> headers, String body) {
			this(status, headers, body, 0);
		}

		public String header(String name) {
			if (headers == null) {
				return null;
			}
			for (Map.Entry<String, List<String>> e : headers.entrySet()) {
				if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))
						&& e.getValue() != null && !e.getValue().isEmpty()) {
					return e.getValue().get(0);
				}
			}
			return null;
		}
	}

	Response get(URI uri, Duration timeout) throws IOException, InterruptedException;

	/** GET with extra request headers (e.g. {@code If-Modified-Since}). Fakes may ignore the headers. */
	default Response get(URI uri, Duration timeout, Map<String, String> headers) throws IOException, InterruptedException {
		return get(uri, timeout);
	}

	/** Reads a response body as it arrives. {@code head} carries status, headers and headersAtMs; its body is empty. */
	interface BodyReader {
		Response read(Response head, InputStream body) throws IOException;
	}

	/**
	 * GET that hands the (decompressed) body to {@code reader} while it is still downloading, so the start of a large
	 * response can be used before the end arrives. Fakes fall back to a buffered {@link #get(URI, Duration, Map)}.
	 */
	default Response getStreaming(URI uri, Duration timeout, Map<String, String> headers, BodyReader reader)
			throws IOException, InterruptedException {
		Response r = get(uri, timeout, headers);
		return reader.read(new Response(r.status(), r.headers(), "", r.headersAtMs()),
				new ByteArrayInputStream(r.body() == null ? new byte[0] : r.body().getBytes(StandardCharsets.UTF_8)));
	}

	Response postJson(URI uri, String json, Duration timeout) throws IOException, InterruptedException;

	/** PATCH with a JSON body (used to edit an existing Discord webhook message). */
	default Response patchJson(URI uri, String json, Duration timeout) throws IOException, InterruptedException {
		throw new IOException("PATCH not supported by this transport");
	}

	/** Request with a JSON body (or none) and extra headers, e.g. a Discord bot's Authorization header. */
	default Response send(String method, URI uri, String json, Duration timeout, Map<String, String> headers)
			throws IOException, InterruptedException {
		throw new IOException(method + " with headers not supported by this transport");
	}
}

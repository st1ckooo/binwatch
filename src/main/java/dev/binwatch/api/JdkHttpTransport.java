package dev.binwatch.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * {@link HttpTransport} backed by {@link java.net.http.HttpClient}, which keeps connections alive and reuses them.
 * GET requests ask for gzip (the auction pages are about half the size compressed) and decompress it here.
 */
public final class JdkHttpTransport implements HttpTransport {
	public static final String USER_AGENT = "BINWatch/1.0 (Fabric mod; read-only auction monitor)";

	private final HttpClient client;

	public JdkHttpTransport(Duration connectTimeout) {
		this.client = HttpClient.newBuilder()
				.connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	@Override
	public Response get(URI uri, Duration timeout) throws IOException, InterruptedException {
		return get(uri, timeout, Map.of());
	}

	private static HttpRequest getRequest(URI uri, Duration timeout, Map<String, String> headers) {
		HttpRequest.Builder b = HttpRequest.newBuilder(uri)
				.timeout(timeout)
				.header("User-Agent", USER_AGENT)
				.header("Accept", "application/json")
				.header("Accept-Encoding", "gzip");
		headers.forEach(b::header);
		return b.GET().build();
	}

	private static boolean gzipped(HttpResponse<?> res) {
		return res.headers().firstValue("Content-Encoding").map(v -> v.trim().equalsIgnoreCase("gzip")).orElse(false);
	}

	@Override
	public Response getStreaming(URI uri, Duration timeout, Map<String, String> headers, BodyReader reader)
			throws IOException, InterruptedException {
		// send() returns as soon as the headers are in; the body is read from the stream as it arrives.
		HttpResponse<InputStream> res = client.send(getRequest(uri, timeout, headers), HttpResponse.BodyHandlers.ofInputStream());
		long headersAt = System.currentTimeMillis();
		try (InputStream raw = res.body(); InputStream in = gzipped(res) ? new GZIPInputStream(raw, 64 * 1024) : raw) {
			return reader.read(new Response(res.statusCode(), res.headers().map(), "", headersAt), in);
		}
	}

	@Override
	public Response get(URI uri, Duration timeout, Map<String, String> headers) throws IOException, InterruptedException {
		long[] headersAt = {0};
		HttpResponse<byte[]> res = client.send(getRequest(uri, timeout, headers), info -> {
			headersAt[0] = System.currentTimeMillis();
			return HttpResponse.BodySubscribers.ofByteArray();
		});
		byte[] body = res.body();
		if (body != null && body.length > 0 && gzipped(res)) {
			try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
				body = in.readAllBytes();
			}
		}
		return new Response(res.statusCode(), res.headers().map(), body == null ? "" : new String(body, StandardCharsets.UTF_8),
				headersAt[0]);
	}

	@Override
	public Response postJson(URI uri, String json, Duration timeout) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(uri)
				.timeout(timeout)
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Response(res.statusCode(), res.headers().map(), res.body());
	}

	@Override
	public Response patchJson(URI uri, String json, Duration timeout) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(uri)
				.timeout(timeout)
				.header("User-Agent", USER_AGENT)
				.header("Content-Type", "application/json")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Response(res.statusCode(), res.headers().map(), res.body());
	}

	@Override
	public Response send(String method, URI uri, String json, Duration timeout, Map<String, String> headers)
			throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", USER_AGENT);
		if (json != null) {
			b.header("Content-Type", "application/json");
		}
		headers.forEach(b::setHeader);
		b.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
		HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		return new Response(res.statusCode(), res.headers().map(), res.body());
	}
}

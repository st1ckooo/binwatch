package dev.binwatch.api;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Offline {@link HttpTransport} that answers from scripted responses. Used by synthetic test mode and unit tests,
 * so no network traffic (and no real Discord webhook) is ever involved.
 */
public final class ScriptedTransport implements HttpTransport {
	public interface Step {
		Response respond() throws IOException;
	}

	private final Map<String, Deque<Step>> scripts = new HashMap<>();
	private final Map<String, Function<URI, Response>> defaults = new HashMap<>();
	public final List<URI> requests = Collections.synchronizedList(new ArrayList<>());
	public final List<String> postedBodies = Collections.synchronizedList(new ArrayList<>());
	/** Request method of each request ("GET", "POST" or "PATCH"), in request order. */
	public final List<String> methods = Collections.synchronizedList(new ArrayList<>());
	/** Extra headers of each GET, in request order (empty map when none). */
	public final List<Map<String, String>> requestHeaders = Collections.synchronizedList(new ArrayList<>());

	/** Queue responses for requests whose path+query contains {@code match}. */
	public synchronized ScriptedTransport enqueue(String match, Step... steps) {
		Deque<Step> d = scripts.computeIfAbsent(match, k -> new ArrayDeque<>());
		for (Step s : steps) {
			d.addLast(s);
		}
		return this;
	}

	/** Response used when no queued step remains for a match. */
	public synchronized ScriptedTransport whenEmpty(String match, Function<URI, Response> fallback) {
		defaults.put(match, fallback);
		return this;
	}

	public static Response ok(String body) {
		return new Response(200, Map.of(), body);
	}

	public static Response status(int status, Map<String, List<String>> headers, String body) {
		return new Response(status, headers, body);
	}

	@Override
	public Response get(URI uri, Duration timeout) throws IOException {
		return get(uri, timeout, Map.of());
	}

	@Override
	public Response get(URI uri, Duration timeout, Map<String, String> headers) throws IOException {
		requestHeaders.add(Map.copyOf(headers));
		methods.add("GET");
		return next(uri);
	}

	@Override
	public Response postJson(URI uri, String json, Duration timeout) throws IOException {
		postedBodies.add(json);
		methods.add("POST");
		return next(uri);
	}

	@Override
	public Response send(String method, URI uri, String json, Duration timeout, Map<String, String> headers)
			throws IOException {
		requestHeaders.add(Map.copyOf(headers));
		postedBodies.add(json == null ? "" : json);
		methods.add(method);
		return next(uri);
	}

	@Override
	public Response patchJson(URI uri, String json, Duration timeout) throws IOException {
		postedBodies.add(json);
		methods.add("PATCH");
		return next(uri);
	}

	private Response next(URI uri) throws IOException {
		requests.add(uri);
		String target = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
		Step step = null;
		Function<URI, Response> fallback = null;
		synchronized (this) {
			String best = null;
			for (String match : scripts.keySet()) {
				if (target.contains(match) && !scripts.get(match).isEmpty() && (best == null || match.length() > best.length())) {
					best = match;
				}
			}
			if (best != null) {
				step = scripts.get(best).pollFirst();
			} else {
				String bestDefault = null;
				for (String match : defaults.keySet()) {
					if (target.contains(match) && (bestDefault == null || match.length() > bestDefault.length())) {
						bestDefault = match;
					}
				}
				fallback = bestDefault == null ? null : defaults.get(bestDefault);
			}
		}
		if (step != null) {
			return step.respond();
		}
		if (fallback != null) {
			return fallback.apply(uri);
		}
		return new Response(404, Map.of(), "{\"success\":false,\"cause\":\"no scripted response\"}");
	}
}

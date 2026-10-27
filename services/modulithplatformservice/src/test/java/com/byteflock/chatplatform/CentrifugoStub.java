package com.byteflock.chatplatform;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * In-process fake of Centrifugo's server API ({@code POST /api/publish} and {@code POST /api/broadcast}).
 * <p>
 * Records every request and supports programmed responses per path, transient failures, downtime (the connection is
 * closed without a response, so the client sees a transport error — the port is never rebound, which is unreliable on
 * Windows), delays for timeout tests and a per-request callback that tests use to move their clock.
 */
public final class CentrifugoStub implements AutoCloseable {

	public record Request(String path, String apiKey, String body) {
	}

	private record Response(int status, String body) {
	}

	public final List<Request> requests = new CopyOnWriteArrayList<>();

	private final HttpServer server;

	private final Map<String, Response> pathResponses = new ConcurrentHashMap<>();

	private final AtomicInteger failNext = new AtomicInteger();

	private volatile int status = 200;

	private volatile String responseBody = "{\"result\":{}}";

	private volatile boolean down = false;

	private volatile Duration delay = Duration.ZERO;

	private volatile Runnable onRequest = () -> {
	};

	public CentrifugoStub() {
		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		this.server.createContext("/", (exchange) -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (this.down) {
				// No response at all: the HTTP client reports a connection/protocol error, like a stopped server.
				exchange.close();
				return;
			}
			this.requests.add(new Request(exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("X-API-Key"), body));
			this.onRequest.run();
			Duration wait = this.delay;
			this.delay = Duration.ZERO;
			if (!wait.isZero()) {
				try {
					Thread.sleep(wait.toMillis());
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			int responseStatus = this.status;
			String responseText = this.responseBody;
			Response perPath = this.pathResponses.get(exchange.getRequestURI().getPath());
			if (perPath != null) {
				responseStatus = perPath.status();
				responseText = perPath.body();
			}
			if (this.failNext.getAndUpdate((count) -> Math.max(0, count - 1)) > 0) {
				responseStatus = 500;
				responseText = "{\"error\":{\"code\":100,\"message\":\"internal\",\"temporary\":true}}";
			}
			byte[] out = responseText.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(responseStatus, out.length);
			exchange.getResponseBody().write(out);
			exchange.close();
		});
		this.server.start();
	}

	public String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** Default response for every path (the publish endpoint of the demo tests). */
	public void respond(int status, String body) {
		this.status = status;
		this.responseBody = body;
	}

	public void respondForPath(String path, int status, String body) {
		this.pathResponses.put(path, new Response(status, body));
	}

	public List<Request> requestsFor(String path) {
		return this.requests.stream().filter((request) -> request.path().equals(path)).toList();
	}

	/** The next {@code count} requests answer HTTP 500 with a temporary error object. */
	public void failNext(int count) {
		this.failNext.set(count);
	}

	public void setDown(boolean isDown) {
		this.down = isDown;
	}

	/** Delay the next response, so a client timeout can be exercised. */
	public void delayNext(Duration duration) {
		this.delay = duration;
	}

	public void onRequest(Runnable callback) {
		this.onRequest = (callback != null) ? callback : () -> {
		};
	}

	/** Forget recorded requests and programmed responses (called by the integration-test base before each test). */
	public void reset() {
		this.requests.clear();
		this.pathResponses.clear();
		this.failNext.set(0);
		this.down = false;
		this.delay = Duration.ZERO;
		this.onRequest = () -> {
		};
		this.respond(200, "{\"result\":{}}");
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

}

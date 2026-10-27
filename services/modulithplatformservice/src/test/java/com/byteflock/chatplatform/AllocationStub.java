package com.byteflock.chatplatform;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;

/**
 * In-process fake of the allocation service's REST API. Handlers can be programmed per {@code METHOD path} (a trailing
 * {@code *} matches a prefix); otherwise defaults behave like a seeded tenant with one available agent {@code agentbob}.
 * Every request (headers and body) is recorded for assertions, and {@link #respondUnavailable(boolean)} simulates a
 * stopped service.
 */
public final class AllocationStub implements AutoCloseable {

	public record Request(String method, String path, Map<String, String> headers, String body) {

		public String header(String name) {
			return this.headers.get(name.toLowerCase(java.util.Locale.ROOT));
		}

	}

	public record Response(int status, String body) {
	}

	public final List<Request> requests = new CopyOnWriteArrayList<>();

	private final Map<String, Function<Request, Response>> handlers = new ConcurrentHashMap<>();

	private final HttpServer server;

	private volatile boolean unavailable = false;

	private volatile Duration delay = Duration.ZERO;

	private volatile Instant clock = Instant.parse("2026-10-27T10:00:00Z");

	public AllocationStub() {
		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		this.server.createContext("/", (exchange) -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			Map<String, String> headers = new ConcurrentHashMap<>();
			exchange.getRequestHeaders().forEach((name, values) -> {
				if (!values.isEmpty()) {
					headers.put(name.toLowerCase(java.util.Locale.ROOT), values.getFirst());
				}
			});
			Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body);
			this.requests.add(request);
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
			Response response = this.unavailable ? new Response(503, "{\"code\":\"UNAVAILABLE\"}")
					: handler(request);
			byte[] out = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(response.status(), out.length);
			exchange.getResponseBody().write(out);
			exchange.close();
		});
		this.server.start();
	}

	public String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	/** Programs a handler; a trailing {@code *} on {@code path} matches every path with that prefix. */
	public void on(String method, String path, Function<Request, Response> handler) {
		this.handlers.put(method + " " + path, handler);
	}

	public void respond(String method, String path, int status, String body) {
		on(method, path, (request) -> new Response(status, body));
	}

	public void respondUnavailable(boolean isUnavailable) {
		this.unavailable = isUnavailable;
	}

	public void delayNext(Duration duration) {
		this.delay = duration;
	}

	/** The engine clock the stub reports in presence records and decisions. */
	public void setClock(Instant instant) {
		this.clock = instant;
	}

	public List<Request> requests(String method, String pathPrefix) {
		return this.requests.stream()
			.filter((request) -> request.method().equals(method) && request.path().startsWith(pathPrefix))
			.toList();
	}

	public int exact(String method, String path) {
		return (int) this.requests.stream()
			.filter((request) -> request.method().equals(method) && request.path().equals(path))
			.count();
	}

	public int count(String method, String pathPrefix) {
		return requests(method, pathPrefix).size();
	}

	public void reset() {
		this.requests.clear();
		this.handlers.clear();
		this.unavailable = false;
		this.delay = Duration.ZERO;
	}

	private Response handler(Request request) {
		Function<Request, Response> exact = this.handlers.get(request.method() + " " + request.path());
		if (exact != null) {
			return exact.apply(request);
		}
		for (Map.Entry<String, Function<Request, Response>> entry : this.handlers.entrySet()) {
			String key = entry.getKey();
			if (key.endsWith("*")) {
				String prefix = key.substring(0, key.length() - 1);
				if ((request.method() + " " + request.path()).startsWith(prefix)) {
					return entry.getValue().apply(request);
				}
			}
		}
		return defaultResponse(request);
	}

	private Response defaultResponse(Request request) {
		String path = request.path();
		String method = request.method();
		if (method.equals("GET") && path.equals("/api/v1/health")) {
			return json(200, "{\"status\":\"UP\"}");
		}
		if (method.equals("POST") && path.equals("/api/v1/conversations")) {
			return json(201, "{\"status\":\"QUEUED\",\"assignedAgentId\":null}");
		}
		if (method.equals("POST") && (path.equals("/api/v1/allocations/dispatch")
				|| path.equals("/api/v1/allocations/claim") || path.endsWith("/reassign"))) {
			return json(200, decision("ASSIGNED", "agentbob"));
		}
		if (method.equals("GET") && path.startsWith("/api/v1/conversations/")) {
			return json(200, "{\"status\":\"QUEUED\",\"assignedAgentId\":null}");
		}
		if (method.equals("POST") && (path.endsWith("/resolve") || path.endsWith("/requeue"))) {
			return json(200, "{}");
		}
		if (method.equals("POST") && path.equals("/api/v1/tenants")) {
			return json(201, "{}");
		}
		if (method.equals("GET") && path.equals("/api/v1/queues")) {
			return json(200, "[]");
		}
		if (method.equals("POST") && path.equals("/api/v1/queues")) {
			return json(201, "{}");
		}
		if (method.equals("GET") && path.startsWith("/api/v1/agents/")) {
			return json(404, "{\"code\":\"AGENT_NOT_FOUND\"}");
		}
		if (method.equals("POST") && path.startsWith("/api/v1/agents/") && path.endsWith("/presence")) {
			return json(200, presence("agentbob", "ONLINE"));
		}
		if (method.equals("POST") && path.equals("/api/v1/agents")) {
			return json(201, "{}");
		}
		if (method.equals("GET") && path.equals("/api/v1/agents")) {
			return json(200, "[]");
		}
		if (method.equals("GET") && path.startsWith("/api/v1/policies/")) {
			return json(404, "{\"code\":\"POLICY_NOT_FOUND\"}");
		}
		if (method.equals("POST") && path.equals("/api/v1/policies")) {
			return json(201, "{\"state\":\"DRAFT\"}");
		}
		if (method.equals("POST") && path.endsWith("/publish")) {
			return json(200, "{}");
		}
		return json(404, "{\"code\":\"NOT_FOUND\"}");
	}

	public String decision(String outcome, String agentId) {
		return "{\"outcome\":\"" + outcome + "\",\"agentId\":"
				+ (agentId != null ? "\"" + agentId + "\"" : "null") + ",\"decisionId\":\"" + UUID.randomUUID()
				+ "\",\"reasonCodes\":[],\"explanation\":\"stub decision\"}";
	}

	public String presence(String agentId, String status) {
		return "{\"id\":\"" + agentId + "\",\"status\":\"" + status + "\",\"lastHeartbeatAt\":\"" + this.clock
				+ "\",\"enabled\":true}";
	}

	private static Response json(int status, String body) {
		return new Response(status, body);
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

}

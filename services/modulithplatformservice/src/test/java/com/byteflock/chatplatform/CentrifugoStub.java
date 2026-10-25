package com.byteflock.chatplatform;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

/** In-process fake of Centrifugo's {@code POST /api/publish} that records requests. */
public final class CentrifugoStub implements AutoCloseable {

	public record Request(String path, String apiKey, String body) {
	}

	public final List<Request> requests = new CopyOnWriteArrayList<>();

	private final HttpServer server;

	private volatile int status = 200;

	private volatile String responseBody = "{\"result\":{}}";

	public CentrifugoStub() {
		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		this.server.createContext("/", (exchange) -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			this.requests.add(new Request(exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("X-API-Key"), body));
			byte[] out = this.responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(this.status, out.length);
			exchange.getResponseBody().write(out);
			exchange.close();
		});
		this.server.start();
	}

	public String url() {
		return "http://127.0.0.1:" + this.server.getAddress().getPort();
	}

	public void respond(int status, String body) {
		this.status = status;
		this.responseBody = body;
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

}

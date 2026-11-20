package tz.co.nlolo.lifeplatform;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-process stand-in for Authentik's API (2026-11-19, the Authentik migration): answers scripted responses by
 * "METHOD /path" (query string ignored) and records every request, for the login adapters' contract tests. Unscripted
 * requests answer 404, as Authentik does for an object the token may not see. The response bodies the tests script are
 * copied from the dev Authentik 2026.8.5.
 */
public final class FakeAuthentik implements AutoCloseable {

    public record Request(String method, String path, String query, String body, String authorization, String upgrade,
                          String transferEncoding) {}

    private record Reply(int status, String body) {}

    private final HttpServer server;
    private final Map<String, List<Reply>> replies = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();

    public FakeAuthentik() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Scripts the next answer to "METHOD /path"; several scripted answers are given in order, the last one repeats. */
    public FakeAuthentik on(String methodAndPath, int status, String body, String... laterBodies) {
        List<Reply> scripted = replies.computeIfAbsent(methodAndPath, k -> new ArrayList<>());
        scripted.add(new Reply(status, body));
        for (String later : laterBodies) scripted.add(new Reply(status, later));
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** The requests other than reads, in order: what the adapter changed. */
    public List<String> writes() {
        return requests.stream().filter(r -> !r.method().equals("GET")).map(r -> r.method() + " " + r.path()).toList();
    }

    public Request last(String methodAndPath) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            Request r = requests.get(i);
            if ((r.method() + " " + r.path()).equals(methodAndPath)) return r;
        }
        throw new AssertionError("No request " + methodAndPath + " in " + requests);
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getQuery(), body, exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("Upgrade"), exchange.getRequestHeaders().getFirst("Transfer-Encoding")));
        List<Reply> scripted = replies.get(key);
        Reply reply = scripted == null || scripted.isEmpty() ? new Reply(404, "{\"detail\":\"Not found.\"}")
            : scripted.size() == 1 ? scripted.get(0) : scripted.remove(0);
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), reply.status() == 204 ? -1 : bytes.length);
        if (reply.status() != 204) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

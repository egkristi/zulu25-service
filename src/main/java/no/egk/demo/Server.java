package no.egk.demo;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The HTTP layer, built on the JDK's own {@code com.sun.net.httpserver} so the service
 * has zero runtime dependencies. Requests are served on virtual threads, which means
 * blocking code in a handler costs a few hundred bytes instead of a platform thread.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /}            - service and runtime info</li>
 *   <li>{@code GET /healthz}     - liveness: is the process alive?</li>
 *   <li>{@code GET /readyz}      - readiness: should it receive traffic?</li>
 *   <li>{@code GET /api/greet}   - example endpoint, optional {@code ?name=}</li>
 * </ul>
 */
public final class Server implements AutoCloseable {

    private static final int MAX_NAME_LENGTH = 100;

    // No route on this service reads the request body; the cap only exists so an
    // untrusted client can't force unbounded memory use via readAllBytes().
    private static final int MAX_REQUEST_BODY_BYTES = 65_536;

    private final HttpServer http;
    private final ExecutorService executor;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final Instant startedAt = Instant.now();
    private final BuildInfo build = BuildInfo.current();

    public Server(int port) throws IOException {
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http.setExecutor(executor);

        // Probes are deliberately not access-logged: they fire every few seconds.
        http.createContext("/healthz", this::handleLive);
        http.createContext("/readyz", this::handleReady);

        logged("/", this::handleRoot);
        logged("/api/greet", this::handleGreet);
    }

    private void logged(String path, HttpHandler handler) {
        http.createContext(path, handler).getFilters().add(accessLog());
    }

    /** Starts accepting connections. The service is live but not yet ready. */
    public void start() {
        http.start();
    }

    /** Flips {@code /readyz} to 200 - call when the service can serve traffic. */
    public void markReady() {
        ready.set(true);
    }

    /** Flips {@code /readyz} to 503 - call first thing on shutdown. */
    public void markNotReady() {
        ready.set(false);
    }

    /** The port actually bound; useful when the server was created with port 0. */
    public int port() {
        return http.getAddress().getPort();
    }

    /** Stops the listener and waits up to {@code grace} for in-flight exchanges. */
    public void stop(Duration grace) {
        markNotReady();
        http.stop((int) Math.max(0, grace.toSeconds()));
        executor.shutdown();
    }

    @Override
    public void close() {
        stop(Duration.ZERO);
    }

    // ---------------------------------------------------------------- handlers

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"/".equals(exchange.getRequestURI().getPath())) {
            respondJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        if (!isRead(exchange)) {
            respondMethodNotAllowed(exchange);
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", build.name());
        body.put("version", build.version());
        body.put("buildTime", build.buildTime());
        body.put("java", Runtime.version().toString());
        body.put("vendor", System.getProperty("java.vm.vendor", "unknown"));
        body.put("host", hostname());
        body.put("uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds());
        body.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        body.put("maxHeapMb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        respondJson(exchange, 200, body);
    }

    private void handleLive(HttpExchange exchange) throws IOException {
        // Liveness must only fail when a restart is the right remedy.
        respondJson(exchange, 200, Map.of("status", "UP"));
    }

    private void handleReady(HttpExchange exchange) throws IOException {
        boolean up = ready.get();
        respondJson(exchange, up ? 200 : 503, Map.of("status", up ? "READY" : "NOT_READY"));
    }

    private void handleGreet(HttpExchange exchange) throws IOException {
        if (!isRead(exchange)) {
            respondMethodNotAllowed(exchange);
            return;
        }
        String name = queryParam(exchange.getRequestURI(), "name").orElse("verden");
        if (name.length() > MAX_NAME_LENGTH) {
            respondJson(exchange, 400, Map.of("error", "name is too long"));
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("greeting", "Hei, " + name + "!");
        body.put("servedBy", hostname());
        body.put("thread", Thread.currentThread().toString());
        body.put("timestamp", Instant.now().toString());
        respondJson(exchange, 200, body);
    }

    // ----------------------------------------------------------------- plumbing

    /** GET and HEAD are both safe reads; everything else is rejected. */
    private static boolean isRead(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static void respondMethodNotAllowed(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        respondJson(exchange, 405, Map.of("error", "method not allowed"));
    }

    private static void respondJson(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        try (exchange) {
            if (!drainRequestBody(exchange)) {
                writeResponse(exchange, 413, Json.object(Map.of("error", "request body too large")));
                return;
            }
            writeResponse(exchange, status, Json.object(body));
        }
    }

    /** Reads and discards up to {@link #MAX_REQUEST_BODY_BYTES}; returns false if the body exceeds it. */
    private static boolean drainRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BODY_BYTES) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void writeResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] payload = (json + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");

        boolean head = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head ? -1 : payload.length);
        if (!head) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }

    private static Optional<String> queryParam(URI uri, String key) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(decode(rawKey))) {
                String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
                return Optional.ofNullable(decode(rawValue));
            }
        }
        return Optional.empty();
    }

    /** Malformed percent-encoding must never crash a handler - treat it as absent instead. */
    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hostname() {
        String fromEnv = System.getenv("HOSTNAME"); // Kubernetes sets this to the pod name
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private static Filter accessLog() {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                long start = System.nanoTime();
                try {
                    chain.doFilter(exchange);
                } finally {
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    Log.info("%s %s -> %d (%d ms)".formatted(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI(),
                            exchange.getResponseCode(),
                            millis));
                }
            }

            @Override
            public String description() {
                return "access-log";
            }
        };
    }
}

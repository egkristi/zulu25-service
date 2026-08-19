package no.egk.demo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security-focused checks: hostile or malformed input must never crash a handler,
 * leak internals, or let user input escape the JSON body into HTTP headers.
 */
class SecurityTest {

    private static Server server;
    private static HttpClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = new Server(0);
        server.start();
        server.markReady();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("malformed percent-encoding in the request line is rejected cleanly, not a crash or hang")
    void malformedPercentEncodingIsRejectedCleanly() throws Exception {
        // java.net.URI itself rejects invalid escapes, so this has to go over a raw
        // socket to actually reach the server with bytes an HttpClient would refuse to send.
        // The JDK's own HttpServer validates the request line before any handler runs -
        // Server.decode()'s own IllegalArgumentException guard is defence in depth for
        // that same failure mode, in case that upstream validation is ever bypassed.
        String response = rawGet("/api/greet?name=%zz");
        assertTrue(response.startsWith("HTTP/1.1 400"), response);
    }

    @Test
    @DisplayName("truncated percent-encoding at the end of the request line is rejected cleanly")
    void truncatedPercentEncodingIsRejectedCleanly() throws Exception {
        String response = rawGet("/api/greet?name=%2");
        assertTrue(response.startsWith("HTTP/1.1 400"), response);
    }

    @Test
    @DisplayName("a lone percent sign in the request line is rejected cleanly")
    void lonePercentSignIsRejectedCleanly() throws Exception {
        String response = rawGet("/api/greet?name=%");
        assertTrue(response.startsWith("HTTP/1.1 400"), response);
    }

    @Test
    @DisplayName("a percent-encoded null byte is escaped, not passed through raw")
    void nullByteInNameIsEscaped() throws Exception {
        HttpResponse<String> response = get("/api/greet?name=%00");
        assertEquals(200, response.statusCode());
        String backslash = String.valueOf('\\');
        assertTrue(response.body().contains(backslash + "u0000"), response.body());
    }

    @Test
    @DisplayName("CRLF in a query value stays inside the escaped JSON body, never becomes a header")
    void crlfInNameCannotInjectHeaders() throws Exception {
        String encoded = URLEncoder.encode("line1\r\nX-Injected: evil", StandardCharsets.UTF_8);
        HttpResponse<String> response = get("/api/greet?name=" + encoded);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("line1\\r\\nX-Injected"), response.body());
        assertTrue(response.headers().firstValue("x-injected").isEmpty(), "CRLF must not inject a response header");
    }

    @Test
    @DisplayName("quotes in a query value cannot break out of the JSON string")
    void quotesInNameCannotBreakJsonStructure() throws Exception {
        String payload = "\",\"admin\":true,\"x\":\"";
        String encoded = URLEncoder.encode(payload, StandardCharsets.UTF_8);
        HttpResponse<String> response = get("/api/greet?name=" + encoded);
        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains(",\"admin\":true,"), "unescaped quotes must not add a JSON key: " + response.body());
        assertTrue(response.body().contains("\\\"admin\\\":true"), response.body());
    }

    @Test
    @DisplayName("script content is served as JSON text, never as renderable HTML")
    void scriptContentIsNotReflectedAsHtml() throws Exception {
        String encoded = URLEncoder.encode("<script>alert(1)</script>", StandardCharsets.UTF_8);
        HttpResponse<String> response = get("/api/greet?name=" + encoded);
        assertEquals(200, response.statusCode());
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("content-type").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElseThrow());
    }

    @Test
    @DisplayName("unsafe HTTP methods are rejected on every route")
    void unsafeMethodsAreRejectedOnEveryRoute() throws Exception {
        for (String path : List.of("/", "/api/greet")) {
            for (String method : List.of("PUT", "DELETE", "PATCH", "OPTIONS", "FOO")) {
                HttpRequest request = HttpRequest.newBuilder(uri(path))
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(405, response.statusCode(), method + " " + path);
                assertEquals("GET, HEAD", response.headers().firstValue("allow").orElseThrow());
            }
        }
    }

    @Test
    @DisplayName("a large number of query parameters does not cause excessive delay")
    void manyQueryParametersDoNotCauseExcessiveDelay() throws Exception {
        StringBuilder query = new StringBuilder("junk0=x");
        for (int i = 1; i < 2000; i++) {
            query.append("&junk").append(i).append("=x");
        }
        query.append("&name=Erling"); // placed last so lookup scans the whole query string

        long start = System.nanoTime();
        HttpResponse<String> response = get("/api/greet?" + query);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(200, response.statusCode());
        assertTrue(elapsedMs < 5000, "took " + elapsedMs + " ms for 2000 query params");
    }

    @Test
    @DisplayName("error responses do not leak stack traces or internal package names")
    void errorResponsesDoNotLeakInternalDetails() throws Exception {
        List<HttpResponse<String>> errorResponses = List.of(
                get("/nope"),
                get("/api/greet?name=" + "a".repeat(101)),
                client.send(HttpRequest.newBuilder(uri("/")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                        HttpResponse.BodyHandlers.ofString()));

        for (HttpResponse<String> response : errorResponses) {
            String body = response.body().toLowerCase(Locale.ROOT);
            assertFalse(body.contains("exception"), response.body());
            assertFalse(body.contains("no.egk.demo"), response.body());
            assertFalse(body.contains("\tat "), response.body());
            assertFalse(body.contains("java.base"), response.body());
        }
    }

    @Test
    @DisplayName("security headers are present on error responses too, not just success")
    void securityHeadersPresentOnErrorResponses() throws Exception {
        for (HttpResponse<String> response : List.of(get("/nope"), get("/api/greet?name=" + "a".repeat(101)))) {
            assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
            assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElseThrow());
        }
    }

    @Test
    @DisplayName("the root endpoint does not leak secret-looking environment data")
    void rootResponseDoesNotLeakSecrets() throws Exception {
        String body = get("/").body().toLowerCase(Locale.ROOT);
        for (String needle : List.of("password", "secret", "token", "apikey", "api_key")) {
            assertFalse(body.contains(needle), "unexpected '" + needle + "' in: " + body);
        }
    }

    @Test
    @DisplayName("oversized request bodies are rejected instead of buffered without limit")
    void oversizedRequestBodyIsRejected() throws Exception {
        byte[] hugeBody = new byte[200_000]; // comfortably over Server.MAX_REQUEST_BODY_BYTES
        Arrays.fill(hugeBody, (byte) 'x');
        HttpRequest request = HttpRequest.newBuilder(uri("/api/greet"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(hugeBody))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a raw, unvalidated request line - bypasses java.net.URI's strict escape checking. */
    private static String rawGet(String requestTarget) throws IOException {
        try (Socket socket = new Socket("localhost", server.port())) {
            socket.setSoTimeout(5000);
            String request = "GET " + requestTarget + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }
}

package no.egk.demo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts the real server on an ephemeral port and talks to it over HTTP,
 * so routing, status codes and JSON encoding are all covered end to end.
 */
class ServerTest {

    private static Server server;
    private static HttpClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = new Server(0);
        server.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(Duration.ZERO);
        }
    }

    @Test
    @DisplayName("liveness is up as soon as the server is started")
    void livenessIsUp() throws Exception {
        HttpResponse<String> response = get("/healthz");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"UP\""), response.body());
    }

    @Test
    @DisplayName("readiness is 503 until the application marks itself ready")
    void readinessFlips() throws Exception {
        server.markNotReady();
        assertEquals(503, get("/readyz").statusCode());

        server.markReady();
        HttpResponse<String> ready = get("/readyz");
        assertEquals(200, ready.statusCode());
        assertTrue(ready.body().contains("READY"), ready.body());
    }

    @Test
    @DisplayName("the greeting endpoint echoes the name parameter")
    void greetUsesNameParameter() throws Exception {
        HttpResponse<String> response = get("/api/greet?name=Erling%20%22E%22");
        assertEquals(200, response.statusCode());
        assertEquals("application/json; charset=utf-8", response.headers().firstValue("content-type").orElseThrow());
        assertTrue(response.body().contains("Hei, Erling \\\"E\\\"!"), response.body());
    }

    @Test
    @DisplayName("the greeting endpoint has a Norwegian default")
    void greetHasDefault() throws Exception {
        assertTrue(get("/api/greet").body().contains("Hei, verden!"));
    }

    @Test
    @DisplayName("the root endpoint reports version and Java runtime")
    void rootReportsBuildInfo() throws Exception {
        HttpResponse<String> response = get("/");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"java\":"), response.body());
        assertTrue(response.body().contains("\"version\":"), response.body());
    }

    @Test
    @DisplayName("unknown paths return 404")
    void unknownPathIsNotFound() throws Exception {
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    @DisplayName("non-GET methods are rejected")
    void postIsNotAllowed() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/greet"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        assertEquals("GET, HEAD", response.headers().firstValue("allow").orElseThrow());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }
}

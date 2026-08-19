package no.egk.demo;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point.
 *
 * <p>Reads its configuration from the environment (12-factor style), starts the HTTP
 * server and blocks until the JVM receives SIGTERM / SIGINT. Kubernetes sends SIGTERM
 * before removing the pod, so the shutdown hook is what makes rolling updates graceful.
 */
public final class Application {

    private Application() {
    }

    // server is closed via the shutdown hook's server.stop(grace), not in this method - it must outlive main().
    @SuppressWarnings("resource")
    public static void main(String[] args) throws InterruptedException {
        int port = intEnv("PORT", 8080);
        Duration grace = Duration.ofSeconds(intEnv("SHUTDOWN_GRACE_SECONDS", 10));
        Duration drain = Duration.ofSeconds(intEnv("SHUTDOWN_DRAIN_SECONDS", 5));

        BuildInfo build = BuildInfo.current();
        Server server;
        try {
            server = new Server(port);
            server.start();
        } catch (Exception e) {
            Log.error("Failed to start on port " + port, e);
            Runtime.getRuntime().halt(1);
            return;
        }

        Log.info("%s %s listening on port %d (Java %s, %s, pid %d)".formatted(
                build.name(),
                build.version(),
                server.port(),
                Runtime.version().toString(),
                System.getProperty("java.vm.vendor", "unknown"),
                ProcessHandle.current().pid()));

        // Anything that has to happen before we accept traffic (warm caches, verify
        // downstream connectivity, run migrations) belongs here - readiness flips after.
        server.markReady();
        Log.info("Ready - /readyz now returns 200");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Shutdown signal received - failing readiness and draining connections");
            server.markNotReady();
            // Give the kubelet / service proxy time to remove this pod from the endpoints
            // list before we close the listener, so in-flight requests are not reset.
            sleep(drain);
            server.stop(grace);
            Log.info("Stopped cleanly");
            stopped.countDown();
        }, "shutdown"));

        stopped.await();
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int intEnv(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            Log.warn("Ignoring invalid value for %s: '%s' - using %d".formatted(name, raw, fallback));
            return fallback;
        }
    }
}

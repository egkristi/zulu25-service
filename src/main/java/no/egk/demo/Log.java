package no.egk.demo;

import java.io.PrintStream;
import java.time.Instant;

/**
 * One-line logging to stdout - which is exactly what a container runtime wants.
 * No logging framework, no config file, no classpath scanning at startup.
 *
 * <p>If you later need MDC, sampling or JSON encoding, replace this class with
 * SLF4J + Logback; nothing else in the codebase has to change.
 */
final class Log {

    private Log() {
    }

    static void info(String message) {
        write(System.out, "INFO", message);
    }

    static void warn(String message) {
        write(System.out, "WARN", message);
    }

    static void error(String message, Throwable cause) {
        write(System.err, "ERROR", message);
        if (cause != null) {
            cause.printStackTrace(System.err);
        }
    }

    private static void write(PrintStream out, String level, String message) {
        out.printf("%s %-5s [%s] %s%n", Instant.now(), level, threadName(), message);
    }

    /** Virtual threads are unnamed by default, so fall back to the thread id. */
    private static String threadName() {
        Thread current = Thread.currentThread();
        String name = current.getName();
        return name.isBlank() ? "vthread-" + current.threadId() : name;
    }
}

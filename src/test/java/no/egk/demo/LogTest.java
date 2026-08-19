package no.egk.demo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the one-line stdout/stderr log format, since there is no logging framework to lean on. */
class LogTest {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void redirectStreams() {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    @DisplayName("info() writes an INFO line to stdout, nothing to stderr")
    void infoWritesToStdout() {
        Log.info("hello");
        String line = out.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("INFO"), line);
        assertTrue(line.contains("hello"), line);
        assertTrue(err.toString(StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    @DisplayName("warn() writes a WARN line to stdout")
    void warnWritesToStdout() {
        Log.warn("careful");
        String line = out.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("WARN"), line);
        assertTrue(line.contains("careful"), line);
    }

    @Test
    @DisplayName("error() writes to stderr and prints the cause's stack trace")
    void errorWritesToStderrWithStackTrace() {
        Log.error("boom", new IllegalStateException("bad state"));
        String line = err.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("ERROR"), line);
        assertTrue(line.contains("boom"), line);
        assertTrue(line.contains("IllegalStateException"), line);
        assertTrue(line.contains("bad state"), line);
    }

    @Test
    @DisplayName("error() without a cause skips the stack trace but still logs the message")
    void errorWithoutCauseSkipsStackTrace() {
        Log.error("boom", null);
        String line = err.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("ERROR"), line);
        assertTrue(line.contains("boom"), line);
    }
}

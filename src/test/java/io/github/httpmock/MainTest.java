package io.github.httpmock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class MainTest {
    @Test void helpAndInvalidConfigurationReturnDistinctExitCodes() {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"--help"}, new PrintStream(out), new PrintStream(err)));
        assertTrue(out.toString().contains("--config"));
        assertEquals(2, Main.run(new String[]{"--delay=-1"}, new PrintStream(out), new PrintStream(err)));
        assertFalse(err.toString().isBlank());
    }

    @Test void cliRunsInOrderContinuesAfterTimeoutAndAppliesDelay() throws Exception {
        try (var server = new ServerSocket(0)) {
            List<String> requests = new CopyOnWriteArrayList<>();
            List<Long> accepted = new CopyOnWriteArrayList<>();
            FutureTask<Void> serving = new FutureTask<>(() -> {
                for (int i = 0; i < 2; i++) {
                    try (Socket peer = server.accept()) {
                        accepted.add(System.nanoTime());
                        byte[] bytes = peer.getInputStream().readNBytes(23);
                        requests.add(new String(bytes, StandardCharsets.US_ASCII));
                        if (i == 0) peer.getInputStream().read();
                        else {
                            peer.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                            peer.getOutputStream().flush();
                        }
                    }
                }
                return null;
            });
            Thread.startVirtualThread(serving);
            var output = new ByteArrayOutputStream();
            int code = Main.run(new String[]{"--target-server-url=http://127.0.0.1:" + server.getLocalPort(),
                    "--error-types=invalid-initial-line,invalid-initial-line", "--max-timeout=250", "--delay=100"},
                    new PrintStream(output), System.err);
            serving.get(3, TimeUnit.SECONDS);
            assertEquals(1, code);
            assertEquals(List.of("BROKEN_REQUEST_LINE\r\n\r\n", "BROKEN_REQUEST_LINE\r\n\r\n"), requests);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(accepted.get(1) - accepted.get(0)) >= 300);
            assertTrue(output.toString().contains("TIMEOUT"));
            assertTrue(output.toString().contains("400 Bad Request"));
        }
    }

    @Test void observedErrorResponseIsNotAToolFailure() throws Exception {
        try (var server = new ServerSocket(0)) {
            FutureTask<Void> serving = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    peer.getInputStream().readNBytes(23);
                    peer.getOutputStream().write("HTTP/1.1 500 Server Error\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    peer.getOutputStream().flush();
                }
                return null;
            });
            Thread.startVirtualThread(serving);
            assertEquals(0, Main.run(new String[]{"--target-server-url=http://127.0.0.1:" + server.getLocalPort(),
                    "--error-types=invalid-initial-line", "--delay=0"}, new PrintStream(new ByteArrayOutputStream()), System.err));
            serving.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void interruptedSuiteDoesNotStartAnotherCase() {
        ClientConfig c = new ClientConfig("cli", URI.create("http://localhost:8080"),
                List.of(ErrorType.INVALID_HEADER, ErrorType.INVALID_INITIAL_LINE), 100, 1000, 500, 100);
        Thread.currentThread().interrupt();
        try {
            assertTrue(new SuiteRunner().run(c, c.errorTypes(), e -> {}, (i, r) -> {}).isEmpty());
        } finally { Thread.interrupted(); }
    }
}

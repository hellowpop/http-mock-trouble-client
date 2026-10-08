package io.github.httpmock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ScenarioRunnerTest {
    private ClientConfig config(int port, ErrorType type, int timeout) {
        return new ClientConfig("cli", URI.create("http://127.0.0.1:" + port + "/test?q=1"),
                List.of(type), 0, timeout, 500, 80);
    }

    @Test void sendsMalformedBytesAndReportsErrorResponseWithoutWaitingForEof() throws Exception {
        Map<ErrorType, String> requests = new LinkedHashMap<>();
        requests.put(ErrorType.INVALID_INITIAL_LINE, "BROKEN_REQUEST_LINE\r\n\r\n");
        requests.put(ErrorType.INVALID_HEADER, "GET /test?q=1 HTTP/1.1\r\nHost: %s\r\nBroken Header Without Colon\r\nConnection: close\r\n\r\n");
        requests.put(ErrorType.CONTENT_LENGTH_SHORT, "POST /test?q=1 HTTP/1.1\r\nHost: %s\r\nContent-Length: 10\r\nConnection: close\r\n\r\n12345");
        requests.put(ErrorType.CONTENT_LENGTH_LONG, "POST /test?q=1 HTTP/1.1\r\nHost: %s\r\nContent-Length: 5\r\nConnection: close\r\n\r\n1234567890");
        for (var entry : requests.entrySet()) {
            try (var server = new ServerSocket(0)) {
                String expected = entry.getValue().formatted("127.0.0.1:" + server.getLocalPort());
                FutureTask<String> received = new FutureTask<>(() -> {
                    try (Socket peer = server.accept()) {
                        peer.setSoTimeout(2000);
                        String request = new String(peer.getInputStream().readNBytes(expected.length()), StandardCharsets.US_ASCII);
                        peer.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nContent-Length: 3\r\n\r\nbad".getBytes(StandardCharsets.US_ASCII));
                        peer.getOutputStream().flush();
                        peer.getInputStream().read(); // Client must finish even if server keeps connection open.
                        return request;
                    }
                });
                Thread.startVirtualThread(received);
                List<RunEvent> events = new CopyOnWriteArrayList<>();
                CaseResult result = new ScenarioRunner().run(config(server.getLocalPort(), entry.getKey(), 1500), entry.getKey(), events::add);
                assertEquals(CaseResult.Outcome.ERROR_RESPONSE, result.outcome());
                assertTrue(result.summary().contains("400 Bad Request"));
                assertEquals(expected, received.get(3, TimeUnit.SECONDS));
                assertFalse(events.isEmpty());
            }
        }
    }

    @Test void socketCloseSendsNothingAndHonorsHoldTime() throws Exception {
        try (var server = new ServerSocket(0)) {
            FutureTask<Integer> read = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) { return peer.getInputStream().read(); }
            });
            Thread.startVirtualThread(read);
            CaseResult result = new ScenarioRunner().run(config(server.getLocalPort(), ErrorType.SOCKET_CLOSE, 1500), ErrorType.SOCKET_CLOSE, e -> {});
            assertEquals(CaseResult.Outcome.CLIENT_CLOSED, result.outcome());
            assertTrue(result.elapsedMillis() >= 70);
            assertEquals(-1, read.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void initialLineWaitSendsOnlyFirstLineAndWaitsForServerClose() throws Exception {
        try (var server = new ServerSocket(0)) {
            FutureTask<String> read = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    String line = new String(peer.getInputStream().readNBytes(24), StandardCharsets.US_ASCII);
                    Thread.sleep(100);
                    return line;
                }
            });
            Thread.startVirtualThread(read);
            CaseResult result = new ScenarioRunner().run(config(server.getLocalPort(), ErrorType.INITIAL_LINE_WAIT, 1500), ErrorType.INITIAL_LINE_WAIT, e -> {});
            assertEquals(CaseResult.Outcome.SERVER_CLOSED, result.outcome());
            assertTrue(result.elapsedMillis() >= 90);
            assertEquals("GET /test?q=1 HTTP/1.1\r\n", read.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void noResponseAndTrickleResponseCannotExtendWholeCaseDeadline() throws Exception {
        for (boolean trickle : List.of(false, true)) {
            try (var server = new ServerSocket(0)) {
                FutureTask<Void> serve = new FutureTask<>(() -> {
                    try (Socket peer = server.accept()) {
                        peer.getInputStream().readNBytes(23);
                        if (trickle) {
                            for (int i = 0; i < 20; i++) {
                                try { peer.getOutputStream().write('H'); peer.getOutputStream().flush(); }
                                catch (java.io.IOException e) { break; }
                                Thread.sleep(40);
                            }
                        } else peer.getInputStream().read();
                    }
                    return null;
                });
                Thread.startVirtualThread(serve);
                CaseResult result = new ScenarioRunner().run(config(server.getLocalPort(), ErrorType.INVALID_INITIAL_LINE, 200), ErrorType.INVALID_INITIAL_LINE, e -> {});
                assertEquals(CaseResult.Outcome.TIMEOUT, result.outcome());
                assertTrue(result.elapsedMillis() >= 170 && result.elapsedMillis() < 1200);
                if (trickle) assertTrue(result.summary().contains("H"));
                serve.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test void initialLineStillWaitsForCloseAfterAnErrorResponse() throws Exception {
        try (var server = new ServerSocket(0)) {
            FutureTask<Void> serve = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    peer.getInputStream().readNBytes(24);
                    peer.getOutputStream().write("HTTP/1.1 408 Request Timeout\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    peer.getOutputStream().flush();
                    peer.getInputStream().read();
                }
                return null;
            });
            Thread.startVirtualThread(serve);
            CaseResult result = new ScenarioRunner().run(config(server.getLocalPort(), ErrorType.INITIAL_LINE_WAIT, 200), ErrorType.INITIAL_LINE_WAIT, e -> {});
            assertEquals(CaseResult.Outcome.TIMEOUT, result.outcome());
            assertTrue(result.summary().contains("408"));
            serve.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void fragmentedResponseIsReadUntilHeadersFinish() throws Exception {
        try (var server = new ServerSocket(0)) {
            FutureTask<Void> serve = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    peer.getInputStream().readNBytes(23);
                    for (String part : List.of("HTTP/1.1 ", "500 Server Error\r\n", "Content-Length: 0\r\n\r\n")) {
                        peer.getOutputStream().write(part.getBytes(StandardCharsets.US_ASCII));
                        peer.getOutputStream().flush();
                        Thread.sleep(20);
                    }
                    peer.getInputStream().read();
                }
                return null;
            });
            Thread.startVirtualThread(serve);
            CaseResult result = new ScenarioRunner().run(config(server.getLocalPort(), ErrorType.INVALID_INITIAL_LINE, 1500), ErrorType.INVALID_INITIAL_LINE, e -> {});
            assertEquals(CaseResult.Outcome.ERROR_RESPONSE, result.outcome());
            assertTrue(result.summary().contains("500 Server Error"));
            serve.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void interruptedRunClosesSocketAndReportsCancellation() throws Exception {
        try (var server = new ServerSocket(0)) {
            CountDownLatch connected = new CountDownLatch(1);
            FutureTask<Integer> serve = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    peer.getInputStream().readNBytes(23);
                    connected.countDown();
                    return peer.getInputStream().read();
                }
            });
            Thread.startVirtualThread(serve);
            CompletableFuture<CaseResult> result = new CompletableFuture<>();
            Thread client = Thread.startVirtualThread(() -> result.complete(new ScenarioRunner().run(
                    config(server.getLocalPort(), ErrorType.INVALID_INITIAL_LINE, 5000), ErrorType.INVALID_INITIAL_LINE, e -> {})));
            assertTrue(connected.await(2, TimeUnit.SECONDS));
            client.interrupt();
            assertEquals(CaseResult.Outcome.CANCELLED, result.get(2, TimeUnit.SECONDS).outcome());
            assertEquals(-1, serve.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void informationalResponsesDoNotHideFinalErrorResponse() throws Exception {
        for (boolean coalesced : List.of(false, true)) {
            try (var server = new ServerSocket(0)) {
                FutureTask<Void> serving = new FutureTask<>(() -> {
                    try (Socket peer = server.accept()) {
                        peer.getInputStream().readNBytes(23);
                        byte[] interim = "HTTP/1.1 103 Early Hints\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                        byte[] error = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                        if (coalesced) {
                            var bytes = new java.io.ByteArrayOutputStream();
                            bytes.write(interim); bytes.write(error);
                            peer.getOutputStream().write(bytes.toByteArray());
                        } else {
                            peer.getOutputStream().write(interim);
                            peer.getOutputStream().flush();
                            Thread.sleep(100);
                            peer.getOutputStream().write(error);
                        }
                        peer.getOutputStream().flush();
                    }
                    return null;
                });
                Thread.startVirtualThread(serving);
                CaseResult result = new ScenarioRunner().run(config(server.getLocalPort(), ErrorType.INVALID_INITIAL_LINE, 1500), ErrorType.INVALID_INITIAL_LINE, e -> {});
                assertEquals(CaseResult.Outcome.ERROR_RESPONSE, result.outcome());
                assertTrue(result.summary().contains("400 Bad Request"));
                serving.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test void unicodePathAndQueryUsePercentEncodedWireTarget() throws Exception {
        try (var server = new ServerSocket(0)) {
            String expected = "GET /%ED%95%9C%EA%B8%80?q=%EA%B0%92&raw=%2F HTTP/1.1\r\n";
            FutureTask<String> serving = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    return new String(peer.getInputStream().readNBytes(expected.length()), StandardCharsets.US_ASCII);
                }
            });
            Thread.startVirtualThread(serving);
            ClientConfig c = new ClientConfig("cli", URI.create("http://127.0.0.1:" + server.getLocalPort() + "/한글?q=값&raw=%2F"),
                    List.of(ErrorType.INITIAL_LINE_WAIT), 0, 200, 500, 80);
            new ScenarioRunner().run(c, ErrorType.INITIAL_LINE_WAIT, e -> {});
            assertEquals(expected, serving.get(3, TimeUnit.SECONDS));
        }
    }
}

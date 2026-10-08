package io.github.httpmock;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static io.github.httpmock.CaseResult.Outcome.*;
import static io.github.httpmock.RunEvent.Level.*;

/** One connection per case, bounded by an absolute deadline, including TLS and writes. */
public final class ScenarioRunner {
    private static final int HEADER_LIMIT = 65536;
    private static final Pattern ERROR_STATUS = Pattern.compile("^HTTP/\\d(?:\\.\\d)?\\s+[45]\\d\\d\\b");
    private static final Pattern STATUS = Pattern.compile("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})\\b");

    public CaseResult run(ClientConfig config, ErrorType type, Consumer<RunEvent> events) {
        long start = System.nanoTime();
        Exchange exchange = new Exchange();
        FutureTask<CaseResult.Outcome> task = new FutureTask<>(() -> execute(config, type, exchange, events));
        events.accept(RunEvent.of(INFO, type, "시작: " + config.targetServerUrl() + ", max timeout=" + config.maxTimeout() + " ms"));
        Thread.startVirtualThread(task);
        CaseResult.Outcome outcome;
        String detail = "";
        try {
            long remaining = TimeUnit.MILLISECONDS.toNanos(config.maxTimeout()) - (System.nanoTime() - start);
            outcome = task.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            outcome = TIMEOUT;
            detail = "최대 대기시간 " + config.maxTimeout() + " ms 경과";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = CANCELLED;
            detail = "사용자 중지";
        } catch (ExecutionException e) {
            outcome = e.getCause() instanceof SocketTimeoutException ? TIMEOUT : NETWORK_ERROR;
            detail = e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
        } finally {
            exchange.close();
            task.cancel(true);
        }
        String response = exchange.summary();
        String summary = detail + (response.isEmpty() ? (detail.isEmpty() ? "응답 없음" : "; 응답 없음") : "; 응답: " + response);
        CaseResult result = new CaseResult(type, outcome, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), summary);
        events.accept(RunEvent.of(result.failed() ? ERROR : outcome == ERROR_RESPONSE ? WARN : INFO, type, result.toString()));
        return result;
    }

    private CaseResult.Outcome execute(ClientConfig config, ErrorType type, Exchange exchange,
                                      Consumer<RunEvent> events) throws IOException, InterruptedException {
        try (Socket socket = new Socket()) {
            exchange.attach(socket);
            socket.connect(new InetSocketAddress(config.targetServerUrl().getHost(), config.port()),
                    Math.min(config.connectTimeout(), config.maxTimeout()));
            Socket transport = socket;
            if (config.tls()) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, config.targetServerUrl().getHost(), config.port(), true);
                exchange.attach(ssl);
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.setSoTimeout(config.maxTimeout());
                ssl.startHandshake();
                transport = ssl;
            }
            events.accept(RunEvent.of(INFO, type, "소켓 연결 완료"));
            if (type == ErrorType.SOCKET_CLOSE) {
                Thread.sleep(config.socketCloseDelay());
                return CLIENT_CLOSED;
            }
            byte[] payload = request(config, type).getBytes(StandardCharsets.US_ASCII);
            transport.getOutputStream().write(payload);
            transport.getOutputStream().flush();
            events.accept(RunEvent.of(INFO, type, payload.length + " bytes 전송, 응답 대기"));
            transport.setSoTimeout(config.maxTimeout());
            byte[] buffer = new byte[2048];
            int read;
            while ((read = transport.getInputStream().read(buffer)) != -1) {
                exchange.append(buffer, read);
                if (type != ErrorType.INITIAL_LINE_WAIT) {
                    if (exchange.hasHeaders()) return exchange.errorResponse() ? ERROR_RESPONSE : RESPONSE_RECEIVED;
                    if (exchange.size() >= HEADER_LIMIT) return RESPONSE_TOO_LARGE;
                }
            }
            if (type == ErrorType.INITIAL_LINE_WAIT || exchange.size() == 0) return SERVER_CLOSED;
            return exchange.errorResponse() ? ERROR_RESPONSE : RESPONSE_RECEIVED;
        }
    }

    private String request(ClientConfig config, ErrorType type) {
        String first = "GET " + config.requestTarget() + " HTTP/1.1\r\n";
        String host = "Host: " + config.hostHeader() + "\r\n";
        return switch (type) {
            case INVALID_INITIAL_LINE -> "BROKEN_REQUEST_LINE\r\n\r\n";
            case INITIAL_LINE_WAIT -> first;
            case INVALID_HEADER -> first + host + "Broken Header Without Colon\r\nConnection: close\r\n\r\n";
            case CONTENT_LENGTH_SHORT -> "POST " + config.requestTarget() + " HTTP/1.1\r\n" + host
                    + "Content-Length: 10\r\nConnection: close\r\n\r\n12345";
            case CONTENT_LENGTH_LONG -> "POST " + config.requestTarget() + " HTTP/1.1\r\n" + host
                    + "Content-Length: 5\r\nConnection: close\r\n\r\n1234567890";
            case SOCKET_CLOSE -> "";
        };
    }

    private static final class Exchange {
        private Socket socket;
        private boolean closed;
        private final ByteArrayOutputStream response = new ByteArrayOutputStream();
        synchronized void attach(Socket value) throws IOException {
            if (closed) { value.close(); throw new IOException("케이스 종료됨"); }
            socket = value;
        }
        synchronized void close() {
            closed = true;
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
        synchronized void append(byte[] bytes, int count) {
            response.write(bytes, 0, Math.min(count, HEADER_LIMIT - response.size()));
        }
        synchronized int size() { return response.size(); }
        synchronized boolean hasHeaders() {
            String raw = response.toString(StandardCharsets.ISO_8859_1);
            return raw.indexOf("\r\n\r\n", finalResponseOffset(raw)) >= 0;
        }
        synchronized boolean errorResponse() {
            String raw = response.toString(StandardCharsets.ISO_8859_1);
            return ERROR_STATUS.matcher(raw.substring(finalResponseOffset(raw))).find();
        }
        private int finalResponseOffset(String raw) {
            int start = 0;
            while (start < raw.length()) {
                int end = raw.indexOf("\r\n\r\n", start);
                if (end < 0) break;
                Matcher status = STATUS.matcher(raw.substring(start, end));
                if (!status.find()) break;
                int code = Integer.parseInt(status.group(1));
                if (code < 100 || code >= 200 || code == 101) break;
                start = end + 4;
            }
            return start;
        }
        synchronized String summary() {
            String raw = response.toString(StandardCharsets.ISO_8859_1);
            int length = Math.min(4096, raw.length());
            return raw.substring(0, length).replace("\r", "\\r").replace("\n", "\\n")
                    .replaceAll("[\\p{Cntrl}]", "?") + (raw.length() > length ? " …(요약 생략)" : "");
        }
    }
}

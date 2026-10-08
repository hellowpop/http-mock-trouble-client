package io.github.httpmock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import javax.swing.*;
import java.awt.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ClientPanelTest {
    @Test void individualExecutionUsesSelectedTypeAndShowsResultOnEdt() throws Exception {
        try (var server = new ServerSocket(0)) {
            FutureTask<String> serving = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    String line = new String(peer.getInputStream().readNBytes(23), StandardCharsets.US_ASCII);
                    peer.getOutputStream().write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    return line;
                }
            });
            Thread.startVirtualThread(serving);
            AtomicReference<ClientPanel> panel = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new ClientPanel(new ClientConfig("application", URI.create("http://127.0.0.1:" + server.getLocalPort()),
                        List.of(ErrorType.SOCKET_CLOSE, ErrorType.INVALID_INITIAL_LINE), 0, 1500, 500, 50)));
                JTable table = find(panel.get(), "cases", JTable.class);
                assertEquals(2, table.getRowCount());
                table.setRowSelectionInterval(1, 1);
                find(panel.get(), "run-selected", JButton.class).doClick();
                assertFalse(find(panel.get(), "run-all", JButton.class).isEnabled());
            });
            assertEquals("BROKEN_REQUEST_LINE\r\n\r\n", serving.get(3, TimeUnit.SECONDS));
            awaitIdle(panel.get());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("ERROR_RESPONSE", find(panel.get(), "cases", JTable.class).getValueAt(1, 3));
                assertTrue(find(panel.get(), "logs", JTextArea.class).getText().contains("400 Bad Request"));
            });
        }
    }

    @Test void stopCancelsSocketAndRestoresButtons() throws Exception {
        try (var server = new ServerSocket(0)) {
            CountDownLatch received = new CountDownLatch(1);
            FutureTask<Integer> serving = new FutureTask<>(() -> {
                try (Socket peer = server.accept()) {
                    peer.getInputStream().readNBytes(23);
                    received.countDown();
                    return peer.getInputStream().read();
                }
            });
            Thread.startVirtualThread(serving);
            AtomicReference<ClientPanel> panel = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new ClientPanel(new ClientConfig("application", URI.create("http://127.0.0.1:" + server.getLocalPort()),
                        List.of(ErrorType.INVALID_INITIAL_LINE), 0, 5000, 500, 100)));
                find(panel.get(), "run-all", JButton.class).doClick();
            });
            assertTrue(received.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> find(panel.get(), "stop", JButton.class).doClick());
            assertEquals(-1, serving.get(2, TimeUnit.SECONDS));
            awaitIdle(panel.get());
            SwingUtilities.invokeAndWait(() -> assertEquals("CANCELLED", find(panel.get(), "cases", JTable.class).getValueAt(0, 3)));
        }
    }

    private void awaitIdle(ClientPanel panel) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        AtomicReference<Boolean> idle = new AtomicReference<>(false);
        while (System.nanoTime() < end) {
            SwingUtilities.invokeAndWait(() -> idle.set(find(panel, "run-all", JButton.class).isEnabled()));
            if (idle.get()) return;
            Thread.sleep(20);
        }
        fail("Swing 실행 완료 대기시간 초과");
    }

    static <T extends Component> T find(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName()) && type.isInstance(c)) return type.cast(c);
            if (c instanceof Container container) {
                T nested = find(container, name, type);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}

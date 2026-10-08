package io.github.httpmock;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClientPanel extends JPanel {
    private final ClientConfig config;
    private final DefaultTableModel model;
    private final JTable cases;
    private final JTextArea logs = new JTextArea();
    private final JLabel notification = new JLabel("실행할 타입을 선택하거나 전체 실행을 누르세요.");
    private final JButton selected = new JButton("선택 실행");
    private final JButton all = new JButton("전체 실행");
    private final JButton stop = new JButton("중지");
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private volatile Thread runningThread;
    private boolean busy;

    public ClientPanel(ClientConfig config) {
        super(new BorderLayout(10, 10));
        this.config = config;
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JTextArea settings = new JTextArea("mode: " + config.mode() + "\ntarget-server-url: " + config.targetServerUrl()
                + "\ndelay: " + config.delay() + " ms    max-timeout: " + config.maxTimeout()
                + " ms\nconnect-timeout: " + config.connectTimeout() + " ms    socket-close-delay: " + config.socketCloseDelay() + " ms");
        settings.setEditable(false);
        settings.setName("settings");
        settings.setBorder(BorderFactory.createTitledBorder("적용된 설정"));
        add(settings, BorderLayout.NORTH);
        model = new DefaultTableModel(new Object[]{"#", "타입", "설명", "실행 결과", "시간(ms)"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        for (int i = 0; i < config.errorTypes().size(); i++) {
            ErrorType type = config.errorTypes().get(i);
            model.addRow(new Object[]{i + 1, type.id(), type.description(), "대기", ""});
        }
        cases = new JTable(model);
        cases.setName("cases");
        cases.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cases.setRowHeight(28);
        cases.getColumnModel().getColumn(0).setPreferredWidth(35);
        cases.getColumnModel().getColumn(1).setPreferredWidth(175);
        cases.getColumnModel().getColumn(2).setPreferredWidth(290);
        cases.getColumnModel().getColumn(3).setPreferredWidth(145);
        cases.setRowSelectionInterval(0, 0);
        JScrollPane tableScroll = new JScrollPane(cases);
        tableScroll.setBorder(BorderFactory.createTitledBorder("에러 시나리오 (설정 순서)"));
        logs.setName("logs");
        logs.setEditable(false);
        logs.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logs.setLineWrap(true);
        logs.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logs);
        logScroll.setBorder(BorderFactory.createTitledBorder("실행 로그 / 응답 요약"));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, logScroll);
        split.setResizeWeight(0.45);
        split.setDividerLocation(240);
        add(split, BorderLayout.CENTER);
        selected.setName("run-selected");
        all.setName("run-all");
        stop.setName("stop");
        stop.setEnabled(false);
        JButton clear = new JButton("로그 지우기");
        clear.addActionListener(e -> logs.setText(""));
        selected.addActionListener(e -> {
            int row = cases.getSelectedRow();
            if (row >= 0) start(List.of(row));
        });
        all.addActionListener(e -> {
            List<Integer> rows = new ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) rows.add(i);
            start(rows);
        });
        stop.addActionListener(e -> stop());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(selected); controls.add(all); controls.add(stop); controls.add(clear);
        notification.setName("notification");
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.add(controls, BorderLayout.NORTH);
        footer.add(notification, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
    }

    private void start(List<Integer> rows) {
        if (busy) return;
        stopRequested.set(false);
        setBusy(true);
        notification.setForeground(Color.DARK_GRAY);
        notification.setText("실행 중: " + rows.size() + "개 케이스");
        for (int row : rows) { model.setValueAt("대기", row, 3); model.setValueAt("", row, 4); }
        List<ErrorType> types = rows.stream().map(i -> config.errorTypes().get(i)).toList();
        new SwingWorker<List<CaseResult>, Update>() {
            @Override protected List<CaseResult> doInBackground() {
                runningThread = Thread.currentThread();
                try {
                    if (stopRequested.get()) runningThread.interrupt();
                    return new SuiteRunner().run(config, types,
                            event -> publish(new Update(-1, null, event.toString())),
                            (i, result) -> publish(new Update(rows.get(i), result, null)));
                } finally { runningThread = null; }
            }
            @Override protected void process(List<Update> updates) {
                for (Update update : updates) {
                    if (update.message() != null) appendLog(update.message());
                    if (update.result() != null) {
                        model.setValueAt(update.result().outcome().name(), update.row(), 3);
                        model.setValueAt(update.result().elapsedMillis(), update.row(), 4);
                    }
                }
            }
            @Override protected void done() {
                try {
                    List<CaseResult> results = get();
                    long failures = results.stream().filter(CaseResult::failed).count();
                    String message = (stopRequested.get() ? "중지됨" : "실행 완료") + ": " + results.size()
                            + "/" + rows.size() + "개, 실행 오류 " + failures + "개";
                    notification.setForeground(failures > 0 || stopRequested.get() ? new Color(170, 35, 35) : new Color(20, 110, 55));
                    notification.setText(message);
                    appendLog(message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    notification.setText("실행 완료 처리 중 중단됨");
                } catch (ExecutionException e) {
                    notification.setForeground(new Color(170, 35, 35));
                    notification.setText("실행 오류: " + e.getCause().getMessage());
                    appendLog(notification.getText());
                } finally { setBusy(false); }
            }
        }.execute();
    }

    public void stop() {
        if (!busy) return;
        stopRequested.set(true);
        Thread thread = runningThread;
        if (thread != null) thread.interrupt();
        stop.setEnabled(false);
        notification.setText("중지 요청 처리 중...");
    }
    private void setBusy(boolean value) {
        busy = value;
        selected.setEnabled(!value);
        all.setEnabled(!value);
        stop.setEnabled(value);
    }
    private void appendLog(String text) {
        logs.append(text + "\n");
        if (logs.getDocument().getLength() > 200000) {
            try { logs.getDocument().remove(0, logs.getDocument().getLength() - 150000); }
            catch (javax.swing.text.BadLocationException e) { throw new IllegalStateException(e); }
        }
        logs.setCaretPosition(logs.getDocument().getLength());
    }
    private record Update(int row, CaseResult result, String message) { }
}

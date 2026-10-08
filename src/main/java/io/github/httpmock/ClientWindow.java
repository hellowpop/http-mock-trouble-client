package io.github.httpmock;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public final class ClientWindow {
    private ClientWindow() { }
    public static void open(ClientConfig config) {
        JFrame frame = new JFrame("HTTP Mock Trouble Client");
        ClientPanel panel = new ClientPanel(config);
        frame.setContentPane(panel);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { panel.stop(); }
        });
        frame.setMinimumSize(new Dimension(880, 600));
        frame.setSize(1060, 760);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}

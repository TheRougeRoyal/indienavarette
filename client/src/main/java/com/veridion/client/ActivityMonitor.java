package com.veridion.client;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** Persistent user-visible status and stop control for activity collection. */
final class ActivityMonitor {
    private ActivityMonitor() { }

    static void show(ActivityClient client) {
        try { SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("Activity monitoring");
            JLabel status = new JLabel("Starting monitored activity collection…");
            JButton stop = new JButton("Stop tracking");
            JPanel panel = new JPanel(new BorderLayout(12, 12));
            panel.add(status, BorderLayout.CENTER);
            panel.add(stop, BorderLayout.SOUTH);
            frame.setContentPane(panel);
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.setSize(440, 120);
            frame.setLocationByPlatform(true);
            Runnable stopTracking = () -> { client.close(); frame.dispose(); };
            stop.addActionListener(event -> stopTracking.run());
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { stopTracking.run(); }
            });
            client.setStatusListener(message -> SwingUtilities.invokeLater(() -> status.setText(message)));
            frame.setVisible(true);
        }); } catch (Exception e) { throw new IllegalStateException("Unable to open activity monitor window", e); }
    }
}

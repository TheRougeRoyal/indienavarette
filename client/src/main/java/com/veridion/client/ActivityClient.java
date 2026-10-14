package com.veridion.client;

import com.veridion.shared.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.UUID;
import java.util.concurrent.*;

public class ActivityClient {
    private final String clientId;
    private final String serverHost;
    private IActivityServer server;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final DesktopTracker windowTracker = DesktopTrackers.current();

    public ActivityClient(String serverHost) {
        this.clientId = UUID.randomUUID().toString().substring(0, 8);
        this.serverHost = serverHost;
    }

    public void connect() {
        try {
            Registry registry = LocateRegistry.getRegistry(serverHost, 1099);
            this.server = (IActivityServer) registry.lookup("ActivityServer");
            System.out.println("Connected to server. Client ID: " + clientId);

            server.registerClient(clientId, java.net.InetAddress.getLocalHost().getHostName());
            startHeartbeat();
            startTracking();
        } catch (Exception e) {
            System.err.println("Connection failed: " + e.getMessage());
        }
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (server != null) {
                    server.sendHeartbeat(clientId);
                }
            } catch (Exception e) {
                System.err.println("Heartbeat failed: " + e.getMessage());
            }
        }, 0, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void startTracking() {
        scheduler.scheduleAtFixedRate(() -> {
            DesktopTracker.CaptureResult result = windowTracker.capture();
            reportActivity("[" + result.status() + "] " + result.appName(), result.windowTitle());
        }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void reportActivity(String app, String title) {
        try {
            if (server != null) {
                server.reportActivity(new ActivityEvent(clientId, app, title, java.time.LocalDateTime.now()));
                System.out.println("Reported: " + app + " - " + title);
            }
        } catch (Exception e) {
            System.err.println("Failed to report activity: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.err.println("Activity tracking requires a visible session and explicit user consent.");
            return;
        }
        int consent = javax.swing.JOptionPane.showConfirmDialog(null,
                "This client will periodically send the active application name and window title to the configured server. Start tracking?",
                "Activity tracking consent", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
        if (consent != javax.swing.JOptionPane.YES_OPTION) return;
        String host = args.length > 0 ? args[0] : System.getProperty("activity.server.host", "localhost");
        ActivityClient client = new ActivityClient(host);
        client.connect();
    }
}

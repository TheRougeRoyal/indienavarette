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
    private final NativeWindowTracker windowTracker = NativeWindowTracker.getInstance();

    public ActivityClient(String serverHost) {
        this.clientId = UUID.randomUUID().toString().substring(0, 8);
        this.serverHost = serverHost;
    }

    public void connect() {
        try {
            Registry registry = LocateRegistry.getRegistry(serverHost, 1099);
            this.server = (IActivityServer) registry.lookup("ActivityServer");
            System.out.println("Connected to server. Client ID: " + clientId);

            server.registerClient(clientId, "localhost");
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
            String app = windowTracker.getActiveAppName();
            String title = windowTracker.getActiveWindowTitle();
            reportActivity(app, title);
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
        // Ensure the native library is in the library path
        System.setProperty("java.library.path", "/home/aakashraj/Documents/github-aakash/indienavarette/native/build");

        ActivityClient client = new ActivityClient("localhost");
        client.connect();
    }
}

package com.veridion.client;

import com.veridion.shared.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

public class ActivityClient implements AutoCloseable {
    private final String clientId;
    private final String serverHost;
    private volatile IActivityServer server;
    private volatile String credential;
    private final String enrollmentToken;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "activity-client"); thread.setDaemon(false); return thread;
    });
    private final DesktopTracker windowTracker;
    private final ServerConnector connector;
    private volatile Consumer<String> statusListener = status -> { };
    private volatile boolean started;
    private final RetryBackoff retryBackoff = new RetryBackoff();
    private long nextAttemptNanos;
    private long lastHeartbeatNanos;

    public ActivityClient(String serverHost) {
        this(serverHost, System.getProperty("activity.client.id", ""), System.getProperty("activity.enrollment.token", ""), DesktopTrackers.current(), ServerConnector.rmi());
    }

    ActivityClient(String serverHost, DesktopTracker windowTracker, ServerConnector connector) {
        this(serverHost, UUID.randomUUID().toString(), System.getProperty("activity.enrollment.token", ""), windowTracker, connector);
    }

    public ActivityClient(String serverHost, String clientId, String enrollmentToken) {
        this(serverHost, clientId, enrollmentToken, DesktopTrackers.current(), ServerConnector.rmi());
    }

    ActivityClient(String serverHost, String clientId, String enrollmentToken, DesktopTracker windowTracker, ServerConnector connector) {
        this.clientId = clientId;
        this.serverHost = serverHost;
        this.enrollmentToken = enrollmentToken;
        this.windowTracker = windowTracker;
        this.connector = connector;
    }

    public synchronized void connect() {
        if (started) return;
        started = true;
        scheduler.schedule(this::cycleSafely, 0, TimeUnit.SECONDS);
    }

    void cycleSafely() {
        if (scheduler.isShutdown()) return;
        try { cycleOnce(); }
        finally {
            if (started && !scheduler.isShutdown()) {
                long delayNanos = server != null ? TimeUnit.SECONDS.toNanos(5) : Math.max(0, nextAttemptNanos - System.nanoTime());
                scheduler.schedule(this::cycleSafely, delayNanos, TimeUnit.NANOSECONDS);
            }
        }
    }

    private void cycleOnce() {
        IActivityServer current = server;
        if (current == null) {
            if (System.nanoTime() < nextAttemptNanos) return;
            try {
                if (clientId == null || clientId.isBlank() || enrollmentToken == null || enrollmentToken.length() < 32)
                    throw new IllegalStateException("Configure activity.client.id and its activity.enrollment.token");
                ServerConnector.Connection connection = connector.enroll(serverHost, RmiConfig.port(), clientId, enrollmentToken);
                current = connection.server();
                credential = connection.credential();
                server = current;
                retryBackoff.succeeded();
                lastHeartbeatNanos = 0;
                System.out.println("Connected and enrolled. Client ID: " + clientId);
                publishStatus("Monitoring active · connected as " + clientId);
            } catch (Exception e) { scheduleRetry("Connection/enrollment failed", e); return; }
        }
        try {
            long now = System.nanoTime();
            if (now - lastHeartbeatNanos >= TimeUnit.SECONDS.toNanos(10)) {
                current.sendHeartbeat(clientId, credential);
                lastHeartbeatNanos = now;
            }
            DesktopTracker.CaptureResult result = windowTracker.capture();
            current.reportActivity(new ActivityEvent(clientId, "[" + result.status() + "] " + result.appName(), result.windowTitle(), java.time.LocalDateTime.now()), credential);
        } catch (Exception e) { scheduleRetry("Connection lost", e); }
    }

    private void scheduleRetry(String reason, Exception error) {
        server = null; credential = null;
        long delay = retryBackoff.failedAttemptDelaySeconds();
        nextAttemptNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(delay);
        System.err.println(reason + "; retrying in " + delay + " seconds: " + error.getMessage());
        publishStatus("Monitoring active · disconnected, retrying in " + delay + "s");
    }

    public void reportActivity(String app, String title) {
        IActivityServer current = server;
        if (current == null || credential == null) return;
        try { current.reportActivity(new ActivityEvent(clientId, app, title, java.time.LocalDateTime.now()), credential); }
        catch (Exception e) { scheduleRetry("Failed to report activity", e); }
    }

    @Override public void close() { server = null; credential = null; scheduler.shutdownNow(); }

    public void setStatusListener(Consumer<String> listener) { this.statusListener = listener == null ? status -> { } : listener; }
    private void publishStatus(String status) { statusListener.accept(status); }

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
        String clientId = System.getProperty("activity.client.id", "");
        String enrollment = System.getProperty("activity.enrollment.token", "");
        if (clientId.isBlank() || enrollment.length() < 32) {
            javax.swing.JOptionPane.showMessageDialog(null, "Configure activity.client.id and its unique activity.enrollment.token before starting.", "Client enrollment required", javax.swing.JOptionPane.ERROR_MESSAGE);
            return;
        }
        ActivityClient client = new ActivityClient(host, clientId, enrollment);
        Runtime.getRuntime().addShutdownHook(new Thread(client::close, "activity-client-shutdown"));
        ActivityMonitor.show(client);
        client.connect();
    }
}

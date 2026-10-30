package com.veridion.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import com.veridion.shared.ActivityEvent;
import com.veridion.shared.IActivityServer;
import java.rmi.RemoteException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBackoffTest {
    @Test void closingIsIdempotentAndPreventsSchedulerRestart() {
        ActivityClient client = new ActivityClient("localhost", () -> new DesktopTracker.CaptureResult("SUPPORTED", "Editor", "Document"),
                (host, port, id, token) -> { throw new AssertionError("closed client must not enroll"); });
        client.close();
        client.close();
        client.connect();
    }

    @Test void reconnectionDelayDoublesCapsAndResetsAfterSuccess() {
        RetryBackoff backoff = new RetryBackoff();
        assertEquals(1, backoff.failedAttemptDelaySeconds());
        assertEquals(2, backoff.failedAttemptDelaySeconds());
        assertEquals(4, backoff.failedAttemptDelaySeconds());
        for (int i = 0; i < 8; i++) backoff.failedAttemptDelaySeconds();
        assertEquals(60, backoff.failedAttemptDelaySeconds());
        assertEquals(60, backoff.failedAttemptDelaySeconds());
        backoff.succeeded();
        assertEquals(1, backoff.failedAttemptDelaySeconds());
    }

    @Test void clientReenrollsAndResumesActivityAfterTransientConnectionFailure() throws Exception {
        String prior = System.getProperty("activity.enrollment.token");
        System.setProperty("activity.enrollment.token", "test-enrollment-token-long-enough-12345");
        AtomicInteger attempts = new AtomicInteger();
        TestServer server = new TestServer();
        ServerConnector connector = (host, port, id, token) -> {
            if (attempts.incrementAndGet() == 1) throw new RemoteException("temporary outage");
            return new ServerConnector.Connection(server, "issued-credential");
        };
        ActivityClient client = new ActivityClient("localhost", () -> new DesktopTracker.CaptureResult("SUPPORTED", "Editor", "Document"), connector);
        try {
            client.cycleSafely();
            Thread.sleep(1100);
            client.cycleSafely();
            org.junit.jupiter.api.Assertions.assertEquals(2, attempts.get());
            org.junit.jupiter.api.Assertions.assertEquals(1, server.heartbeats);
            org.junit.jupiter.api.Assertions.assertEquals(1, server.reports);
        } finally {
            client.close();
            if (prior == null) System.clearProperty("activity.enrollment.token");
            else System.setProperty("activity.enrollment.token", prior);
        }
    }

    @Test void captureFailureReportsUnavailableWithoutReenrolling() throws Exception {
        String prior = System.getProperty("activity.enrollment.token");
        System.setProperty("activity.enrollment.token", "test-enrollment-token-long-enough-12345");
        AtomicInteger attempts = new AtomicInteger();
        TestServer server = new TestServer();
        ActivityClient client = new ActivityClient("localhost", () -> {
            throw new IllegalStateException("display disconnected");
        }, (host, port, id, token) -> {
            attempts.incrementAndGet();
            return new ServerConnector.Connection(server, "issued-credential");
        });
        try {
            client.cycleSafely();
            client.cycleSafely();
            assertEquals(1, attempts.get());
            assertEquals(1, server.heartbeats);
            assertEquals(2, server.reports);
            assertTrue(server.lastReportedApp.startsWith("[UNAVAILABLE]"));
        } finally {
            client.close();
            if (prior == null) System.clearProperty("activity.enrollment.token");
            else System.setProperty("activity.enrollment.token", prior);
        }
    }

    private static final class TestServer implements IActivityServer {
        int heartbeats;
        int reports;
        String lastReportedApp;
        @Override public String registerClient(String id, String host, String token) { return "issued-credential"; }
        @Override public void reportActivity(ActivityEvent event, String credential) { reports++; lastReportedApp = event.appName(); }
        @Override public void sendHeartbeat(String id, String credential) { heartbeats++; }
    }
}

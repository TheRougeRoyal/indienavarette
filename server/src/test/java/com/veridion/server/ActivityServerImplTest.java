package com.veridion.server;

import com.veridion.shared.ActivityEvent;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityServerImplTest {
    private static final String ENROLLMENT = "this-is-a-test-enrollment-secret-long-enough";
    private ActivityServerImpl server;

    @BeforeEach void setUp() throws Exception { server = new ActivityServerImpl(ENROLLMENT, Duration.ofMillis(60)); }
    @AfterEach void tearDown() throws Exception { UnicastRemoteObject.unexportObject(server, true); }

    @Test void rejectsInvalidAndUnauthenticatedEnrollment() {
        assertThrows(RemoteException.class, () -> server.registerClient("", "host", ENROLLMENT));
        assertThrows(RemoteException.class, () -> server.registerClient("unknown", "host", ENROLLMENT));
        assertThrows(RemoteException.class, () -> server.registerClient("c1", "host", "wrong"));
        assertTrue(server.getClients().isEmpty());
    }

    @Test void authorizesRegisteredClientAndPreservesSameTimestampEventsInOrder() throws Exception {
        String credential = server.registerClient("c1", "host", ENROLLMENT);
        LocalDateTime sameTime = LocalDateTime.now();
        ActivityEvent first = new ActivityEvent("c1", "Editor", "first", sameTime);
        ActivityEvent second = new ActivityEvent("c1", "Browser", "second", sameTime);
        assertThrows(RemoteException.class, () -> server.reportActivity(first, "bad-credential"));
        server.reportActivity(first, credential);
        server.reportActivity(second, credential);
        var events = server.getLogsForClient("c1").values().stream().toList();
        assertEquals(2, events.size());
        assertEquals("first", events.get(0).windowTitle());
        assertEquals("second", events.get(1).windowTitle());
    }

    @Test void rejectsMalformedReportsAndUnknownClientHeartbeat() throws Exception {
        String credential = server.registerClient("c1", "host", ENROLLMENT);
        assertThrows(RemoteException.class, () -> server.reportActivity(null, credential));
        assertThrows(RemoteException.class, () -> server.sendHeartbeat("missing", credential));
    }

    @Test void heartbeatRefreshesThenExpires() throws Exception {
        String credential = server.registerClient("c1", "host", ENROLLMENT);
        var session = server.getClients().get("c1");
        assertTrue(server.isConnected(session));
        Thread.sleep(30);
        server.sendHeartbeat("c1", credential);
        assertTrue(server.isConnected(session));
        Thread.sleep(80);
        assertFalse(server.isConnected(session));
    }
}

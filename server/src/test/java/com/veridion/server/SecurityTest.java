package com.veridion.server;

import com.veridion.shared.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.Map;
import java.rmi.RemoteException;
import static org.junit.jupiter.api.Assertions.*;

class SecurityTest {
    private ActivityServerImpl server;
    private final String VALID_ID = "test-client";
    private final String VALID_TOKEN = "this-is-a-very-long-secret-token-that-is-at-least-32-chars";

    @BeforeEach
    void setup() throws RemoteException {
        server = new ActivityServerImpl(Map.of(VALID_ID, VALID_TOKEN), 0, Duration.ofSeconds(30));
    }

    @Test
    void testValidEnrollment() throws RemoteException {
        String cred = server.registerClient(VALID_ID, "localhost", VALID_TOKEN);
        assertNotNull(cred);
        assertEquals(64, cred.length());
    }

    @Test
    void testInvalidToken() {
        assertThrows(RemoteException.class, () -> server.registerClient(VALID_ID, "localhost", "wrong-token"));
    }

    @Test
    void testUnknownClient() {
        assertThrows(RemoteException.class, () -> server.registerClient("unknown", "localhost", VALID_TOKEN));
    }

    @Test
    void testUnauthorizedActivity() throws RemoteException {
        String cred = server.registerClient(VALID_ID, "localhost", VALID_TOKEN);
        ActivityEvent event = new ActivityEvent(VALID_ID, "App", "Title", java.time.LocalDateTime.now());
        
        assertThrows(RemoteException.class, () -> server.reportActivity(event, "wrong-credential"));
    }

    @Test
    void testDuplicateRegistrationInvalidatesOldCred() throws RemoteException {
        String cred1 = server.registerClient(VALID_ID, "host1", VALID_TOKEN);
        String cred2 = server.registerClient(VALID_ID, "host2", VALID_TOKEN);
        
        assertNotEquals(cred1, cred2);
        
        ActivityEvent event = new ActivityEvent(VALID_ID, "App", "Title", java.time.LocalDateTime.now());
        assertThrows(RemoteException.class, () -> server.reportActivity(event, cred1), "Old credential should be invalid");
        assertDoesNotThrow(() -> server.reportActivity(event, cred2), "New credential should be valid");
    }

    @Test
    void testHeartbeatExpiry() throws RemoteException, InterruptedException {
        server = new ActivityServerImpl(Map.of(VALID_ID, VALID_TOKEN), 0, Duration.ofMillis(100));
        String cred = server.registerClient(VALID_ID, "localhost", VALID_TOKEN);
        
        assertTrue(server.isConnected(server.getClients().get(VALID_ID)));
        Thread.sleep(200);
        assertFalse(server.isConnected(server.getClients().get(VALID_ID)));
    }
}

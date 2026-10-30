package com.veridion.assistance;

import java.rmi.server.UnicastRemoteObject;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsentClientHandlerTest {
    private static final String TOKEN = "test-operator-token-long-enough-0123456789";
    private ConsentClientHandler handler;
    private List<ConsentClientHandler> handlers;

    @BeforeEach void setUp() throws RemoteException {
        handlers = new ArrayList<>();
        handler = newHandler(operator -> true);
    }

    @AfterEach void tearDown() {
        for (ConsentClientHandler activeHandler : handlers) {
            try { activeHandler.terminateSession(activeHandler.getActiveSessionId(), TOKEN); }
            catch (Exception ignored) { }
            try { UnicastRemoteObject.unexportObject(activeHandler, true); }
            catch (Exception ignored) { }
        }
    }

    private ConsentClientHandler newHandler(Predicate<String> consent) throws RemoteException {
        ConsentClientHandler created = new ConsentClientHandler(consent, TOKEN);
        handlers.add(created);
        return created;
    }

    @Test void consentStartsOneSessionAndTerminationEndsItImmediately() throws Exception {
        String sessionId = handler.requestAssistance("Support operator", TOKEN);
        assertNotNull(sessionId);
        assertFalse(sessionId.isEmpty());
        assertEquals(sessionId, UUID.fromString(sessionId).toString());
        assertTrue(handler.isSessionActive());
        assertEquals(ConsentClientHandler.SessionState.ACTIVE_VIEW, handler.getState());
        assertEquals("", handler.requestAssistance("Another operator", TOKEN));
        assertThrows(java.rmi.RemoteException.class, () -> handler.terminateSession(sessionId, "wrong"));
        handler.terminateSession(sessionId, TOKEN);
        assertFalse(handler.isSessionActive());
        assertNull(handler.getActiveSessionId());
        assertEquals(ConsentClientHandler.SessionState.TERMINATED, handler.getState());
    }

    @Test void deniedConsentDoesNotCreateSession() throws Exception {
        handler = newHandler(operator -> false);
        assertThrows(java.rmi.RemoteException.class, () -> handler.requestAssistance("Support operator", "wrong"));
        assertEquals("", handler.requestAssistance("Support operator", TOKEN));
        assertFalse(handler.isSessionActive());
        assertNull(handler.getActiveSessionId());
        assertEquals(ConsentClientHandler.SessionState.IDLE, handler.getState());
        assertFalse(handler.getAuditLog().getEntries().stream()
                .anyMatch(entry -> entry.action().equals("SESSION_STARTED")));
    }
}

package com.veridion.assistance;

import java.rmi.server.UnicastRemoteObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsentClientHandlerTest {
    private static final String TOKEN = "test-operator-token-long-enough-0123456789";
    @Test void consentStartsOneSessionAndTerminationEndsItImmediately() throws Exception {
        ConsentClientHandler handler = new ConsentClientHandler(operator -> true, TOKEN);
        try {
            String sessionId = handler.requestAssistance("Support operator", TOKEN);
            assertNotNull(sessionId);
            assertFalse(sessionId.isEmpty());
            assertTrue(handler.isSessionActive());
            assertEquals("", handler.requestAssistance("Another operator", TOKEN));
            assertThrows(java.rmi.RemoteException.class, () -> handler.terminateSession(sessionId, "wrong"));
            handler.terminateSession(sessionId, TOKEN);
            assertFalse(handler.isSessionActive());
            assertNull(handler.getActiveSessionId());
            assertEquals(ConsentClientHandler.SessionState.TERMINATED, handler.getState());
        } finally { UnicastRemoteObject.unexportObject(handler, true); }
    }

    @Test void deniedConsentDoesNotCreateSession() throws Exception {
        ConsentClientHandler handler = new ConsentClientHandler(operator -> false, TOKEN);
        try {
            assertThrows(java.rmi.RemoteException.class, () -> handler.requestAssistance("Support operator", "wrong"));
            assertEquals("", handler.requestAssistance("Support operator", TOKEN));
            assertFalse(handler.isSessionActive());
            assertNull(handler.getActiveSessionId());
        } finally { UnicastRemoteObject.unexportObject(handler, true); }
    }
}

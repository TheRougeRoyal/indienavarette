package com.veridion.assistance;

import com.veridion.shared.*;
import org.junit.jupiter.api.*;
import java.time.Duration;
import java.util.Map;
import java.rmi.RemoteException;
import static org.junit.jupiter.api.Assertions.*;

class RemoteInputTest {
    private ConsentClientHandler handler;
    private final String VALID_ID = "test-client";
    private final String VALID_TOKEN = "this-is-a-very-long-secret-token-that-is-at-least-32-chars";

    @BeforeEach
    void setup() throws RemoteException {
        handler = new ConsentClientHandler(operator -> true, VALID_TOKEN);
    }

    @Test
    void testInputRejectedBeforeConsent() throws RemoteException {
        RemoteInputCommand cmd = new RemoteInputCommand(RemoteInputCommand.CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertThrows(RemoteException.class, () -> handler.executeInput("any", VALID_TOKEN, cmd));
    }

    @Test
    void testInputRejectedInViewOnlyState() throws RemoteException {
        String sessionId = handler.requestAssistance("Operator", VALID_TOKEN);
        RemoteInputCommand cmd = new RemoteInputCommand(RemoteInputCommand.CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertThrows(RemoteException.class, () -> handler.executeInput(sessionId, VALID_TOKEN, cmd));
    }

    @Test
    void testInputAcceptedInControlState() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        String sId = autoHandler.requestAssistance("Operator", VALID_TOKEN);
        autoHandler.requestInputControl(sId, VALID_TOKEN);

        RemoteInputCommand cmd = new RemoteInputCommand(RemoteInputCommand.CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertDoesNotThrow(() -> autoHandler.executeInput(sId, VALID_TOKEN, cmd));
    }

    @Test
    void testInputConsentDenialLeavesSessionViewOnly() throws RemoteException {
        ConsentClientHandler viewOnlyHandler = new ConsentClientHandler(op -> true, op -> false, VALID_TOKEN);
        String sessionId = viewOnlyHandler.requestAssistance("Operator", VALID_TOKEN);
        viewOnlyHandler.requestInputControl(sessionId, VALID_TOKEN);

        assertEquals(ConsentClientHandler.SessionState.ACTIVE_VIEW, viewOnlyHandler.getState());
        RemoteInputCommand cmd = new RemoteInputCommand(RemoteInputCommand.CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertThrows(RemoteException.class, () -> viewOnlyHandler.executeInput(sessionId, VALID_TOKEN, cmd));
    }

    @Test
    void testInvalidCoordinatesRejected() {
        RemoteInputCommand badCmd = new RemoteInputCommand(RemoteInputCommand.CommandType.MOUSE_MOVE, -1, 100, 0, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, badCmd::validate);
    }

    @Test
    void testUnauthorizedOperatorRejected() throws RemoteException {
        String sessionId = handler.requestAssistance("Operator", VALID_TOKEN);
        RemoteInputCommand cmd = new RemoteInputCommand(RemoteInputCommand.CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertThrows(RemoteException.class, () -> handler.executeInput(sessionId, "wrong-token", cmd));
    }

    @Test
    void testSessionTerminationBlocksInput() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, VALID_TOKEN);
        String sId = autoHandler.requestAssistance("Operator", VALID_TOKEN);
        autoHandler.requestInputControl(sId, VALID_TOKEN);
        autoHandler.terminateSession(sId, VALID_TOKEN);

        RemoteInputCommand cmd = new RemoteInputCommand(RemoteInputCommand.CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertThrows(RemoteException.class, () -> autoHandler.executeInput(sId, VALID_TOKEN, cmd));
    }
}

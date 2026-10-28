package com.veridion.assistance;

import com.veridion.shared.RemoteInputCommand;
import com.veridion.shared.RemoteInputCommand.CommandType;
import org.junit.jupiter.api.*;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Security-focused unit tests for the remote-assistance module.
 *
 * <p>Covers all 5 hardening issues:
 * <ol>
 *   <li>Input validation (bounds, button codes, key codes, scroll amounts)</li>
 *   <li>Replay protection (stale / duplicate sequence numbers)</li>
 *   <li>Key release on session termination</li>
 *   <li>Operator identity in audit log</li>
 *   <li>Session expiry</li>
 * </ol>
 *
 * <p>Each test obtains a fresh {@link ConsentClientHandler} via {@code @BeforeEach}. Handlers are
 * unexported in {@code @AfterEach} to avoid RMI registry leaks between tests.
 */
class RemoteInputTest {

    /** Shared token long enough to satisfy the ≥32-char requirement. */
    private static final String VALID_TOKEN =
            "this-is-a-very-long-secret-token-that-is-at-least-32-chars";

    /** Handler that auto-accepts screen-capture consent but NOT input consent. */
    private ConsentClientHandler handler;

    @BeforeEach
    void setup() throws RemoteException {
        handler = new ConsentClientHandler(operator -> true, VALID_TOKEN);
    }

    @AfterEach
    void teardown() throws Exception {
        try { UnicastRemoteObject.unexportObject(handler, true); } catch (Exception ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Baseline consent / state-machine tests (9 pre-existing, preserved)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void testInputRejectedBeforeConsent() throws RemoteException {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertThrows(RemoteException.class, () -> handler.executeInput("any", VALID_TOKEN, cmd));
    }

    @Test
    void testInputRejectedInViewOnlyState() throws RemoteException {
        String sessionId = handler.requestAssistance("Operator", VALID_TOKEN);
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertThrows(RemoteException.class, () -> handler.executeInput(sessionId, VALID_TOKEN, cmd));
    }

    @Test
    void testInputAcceptedInControlState() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            String sId = autoHandler.requestAssistance("Operator", VALID_TOKEN);
            autoHandler.requestInputControl(sId, VALID_TOKEN);
            RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
            assertDoesNotThrow(() -> autoHandler.executeInput(sId, VALID_TOKEN, cmd));
        } finally {
            UnicastRemoteObject.unexportObject(autoHandler, true);
        }
    }

    @Test
    void testInputConsentDenialLeavesSessionViewOnly() throws RemoteException {
        ConsentClientHandler viewOnlyHandler =
                new ConsentClientHandler(op -> true, op -> false, VALID_TOKEN);
        try {
            String sessionId = viewOnlyHandler.requestAssistance("Operator", VALID_TOKEN);
            viewOnlyHandler.requestInputControl(sessionId, VALID_TOKEN);
            assertEquals(ConsentClientHandler.SessionState.ACTIVE_VIEW, viewOnlyHandler.getState());
            RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
            assertThrows(RemoteException.class,
                    () -> viewOnlyHandler.executeInput(sessionId, VALID_TOKEN, cmd));
        } finally {
            UnicastRemoteObject.unexportObject(viewOnlyHandler, true);
        }
    }

    @Test
    void testInvalidCoordinatesRejected() {
        RemoteInputCommand badCmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, -1, 100, 0, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, badCmd::validate);
    }

    @Test
    void testInvalidMouseButtonRejected() {
        RemoteInputCommand badCmd = new RemoteInputCommand(CommandType.MOUSE_PRESS, 100, 100, 4, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, badCmd::validate);
    }

    @Test
    void testInvalidScrollAmountRejected() {
        RemoteInputCommand badCmd = new RemoteInputCommand(CommandType.MOUSE_SCROLL, 0, 0, 0, 0, 11, 1);
        assertThrows(IllegalArgumentException.class, badCmd::validate);
    }

    @Test
    void testInvalidKeyCodeRejected() {
        RemoteInputCommand badCmd = new RemoteInputCommand(CommandType.KEY_PRESS, 0, 0, 0, 256, 0, 1);
        assertThrows(IllegalArgumentException.class, badCmd::validate);
    }

    @Test
    void testUnauthorizedOperatorRejected() throws RemoteException {
        String sessionId = handler.requestAssistance("Operator", VALID_TOKEN);
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
        assertThrows(RemoteException.class,
                () -> handler.executeInput(sessionId, "wrong-token", cmd));
    }

    @Test
    void testSessionTerminationBlocksInput() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, VALID_TOKEN);
        try {
            String sId = autoHandler.requestAssistance("Operator", VALID_TOKEN);
            autoHandler.requestInputControl(sId, VALID_TOKEN);
            autoHandler.terminateSession(sId, VALID_TOKEN);
            RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
            assertThrows(RemoteException.class,
                    () -> autoHandler.executeInput(sId, VALID_TOKEN, cmd));
        } finally {
            UnicastRemoteObject.unexportObject(autoHandler, true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue 1: Input Validation – screen-bounds checking
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Coordinates exactly at (0, 0) must be accepted.
     */
    @Test
    void validatesOriginCoordinatesAreAccepted() {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 0, 0, 0, 0, 0, 1);
        assertDoesNotThrow(cmd::validate);
    }

    /**
     * Coordinates one pixel beyond the right/bottom edge of the screen must be rejected.
     */
    @Test
    void rejectsMouseCoordinatesOutsideScreen() {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        RemoteInputCommand outX = new RemoteInputCommand(
                CommandType.MOUSE_MOVE, screen.width, 0, 0, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, outX::validate,
                "x == screen.width should be rejected");

        RemoteInputCommand outY = new RemoteInputCommand(
                CommandType.MOUSE_MOVE, 0, screen.height, 0, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, outY::validate,
                "y == screen.height should be rejected");

        RemoteInputCommand farOut = new RemoteInputCommand(
                CommandType.MOUSE_MOVE, screen.width + 100, screen.height + 100, 0, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, farOut::validate,
                "Wildly out-of-bounds coordinates should be rejected");
    }

    /** Mouse button 0 is outside the 1-3 range. */
    @Test
    void rejectsMouseButtonZero() {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_PRESS, 100, 100, 0, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, cmd::validate);
    }

    /** Mouse button 4 is outside the 1-3 range. */
    @Test
    void rejectsMouseButtonFour() {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_PRESS, 100, 100, 4, 0, 0, 1);
        assertThrows(IllegalArgumentException.class, cmd::validate);
    }

    /** Scroll amount -11 is below the -10 lower bound. */
    @Test
    void rejectsScrollAmountBelowMinus10() {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_SCROLL, 0, 0, 0, 0, -11, 1);
        assertThrows(IllegalArgumentException.class, cmd::validate);
    }

    /** Scroll amount +10 is exactly at the upper bound and must be accepted. */
    @Test
    void acceptsScrollAmountAtUpperBound() {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_SCROLL, 0, 0, 0, 0, 10, 1);
        assertDoesNotThrow(cmd::validate);
    }

    /** Key code 255 is at the upper bound and must be accepted. */
    @Test
    void acceptsKeyCodeAtUpperBound() {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.KEY_PRESS, 0, 0, 0, 255, 0, 1);
        assertDoesNotThrow(cmd::validate);
    }

    /** Key code 256 exceeds the X11 range of 0-255. */
    @Test
    void rejectsKeyCodeAbove255() {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.KEY_PRESS, 0, 0, 0, 256, 0, 1);
        assertThrows(IllegalArgumentException.class, cmd::validate);
    }

    /** Negative key code is below the 0-255 range. */
    @Test
    void rejectsNegativeKeyCode() {
        RemoteInputCommand cmd = new RemoteInputCommand(CommandType.KEY_RELEASE, 0, 0, 0, -1, 0, 1);
        assertThrows(IllegalArgumentException.class, cmd::validate);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue 2: Replay Protection – sequence number enforcement
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A command whose sequence number equals the last accepted one must be rejected.
     * A command with a lower sequence number must also be rejected.
     */
    @Test
    void rejectsStaleSequenceNumbers() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            String sId = autoHandler.requestAssistance("Operator", VALID_TOKEN);
            autoHandler.requestInputControl(sId, VALID_TOKEN);

            RemoteInputCommand cmd1 = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 10);
            autoHandler.executeInput(sId, VALID_TOKEN, cmd1);  // seq=10 accepted

            // Same sequence number → replay
            RemoteInputCommand staleCmd =
                    new RemoteInputCommand(CommandType.MOUSE_MOVE, 200, 200, 0, 0, 0, 10);
            assertThrows(RemoteException.class,
                    () -> autoHandler.executeInput(sId, VALID_TOKEN, staleCmd),
                    "Duplicate sequence number must be rejected");

            // Lower sequence number → older replay
            RemoteInputCommand olderCmd =
                    new RemoteInputCommand(CommandType.MOUSE_MOVE, 300, 300, 0, 0, 0, 5);
            assertThrows(RemoteException.class,
                    () -> autoHandler.executeInput(sId, VALID_TOKEN, olderCmd),
                    "Older sequence number must be rejected");

            // Higher sequence number → must be accepted
            RemoteInputCommand freshCmd =
                    new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 11);
            assertDoesNotThrow(() -> autoHandler.executeInput(sId, VALID_TOKEN, freshCmd));
        } finally {
            UnicastRemoteObject.unexportObject(autoHandler, true);
        }
    }

    /**
     * After a new session is started, the sequence counter must reset so the first command's
     * sequence number is accepted even if it is lower than the previous session's last number.
     */
    @Test
    void sequenceNumberResetsForNewSession() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            // Session 1: send seq=100
            String sId1 = autoHandler.requestAssistance("Operator", VALID_TOKEN);
            autoHandler.requestInputControl(sId1, VALID_TOKEN);
            RemoteInputCommand highSeq = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 100);
            autoHandler.executeInput(sId1, VALID_TOKEN, highSeq);
            autoHandler.terminateSession(sId1, VALID_TOKEN);

            // Session 2: seq=1 must be accepted because the counter was reset
            String sId2 = autoHandler.requestAssistance("Operator", VALID_TOKEN);
            autoHandler.requestInputControl(sId2, VALID_TOKEN);
            RemoteInputCommand lowSeq = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
            assertDoesNotThrow(() -> autoHandler.executeInput(sId2, VALID_TOKEN, lowSeq),
                    "Sequence counter must reset when a new session starts");
        } finally {
            UnicastRemoteObject.unexportObject(autoHandler, true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue 3: Key Release on Termination
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * After pressing a key and then terminating the session, the handler's pressed-keys set must
     * be empty (keys are released and the tracking set is cleared).
     */
    @Test
    void releasesHeldKeysOnTermination() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            String sId = autoHandler.requestAssistance("Operator", VALID_TOKEN);
            autoHandler.requestInputControl(sId, VALID_TOKEN);

            // Press key 'A' (VK_A = 65)
            RemoteInputCommand pressKey =
                    new RemoteInputCommand(CommandType.KEY_PRESS, 0, 0, 0, 65, 0, 100);
            autoHandler.executeInput(sId, VALID_TOKEN, pressKey);
            assertFalse(autoHandler.getPressedKeys().isEmpty(),
                    "Key should be in the pressed set after KEY_PRESS");

            // Terminate – must release all keys
            autoHandler.terminateSession(sId, VALID_TOKEN);
            assertTrue(autoHandler.getPressedKeys().isEmpty(),
                    "Pressed-keys set must be empty after session termination");
        } finally {
            UnicastRemoteObject.unexportObject(autoHandler, true);
        }
    }

    /**
     * A KEY_RELEASE command removes the key from the pressed-keys set while the session is active.
     */
    @Test
    void keyReleaseCommandRemovesKeyFromPressedSet() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            String sId = autoHandler.requestAssistance("Operator", VALID_TOKEN);
            autoHandler.requestInputControl(sId, VALID_TOKEN);

            RemoteInputCommand press =
                    new RemoteInputCommand(CommandType.KEY_PRESS, 0, 0, 0, 65, 0, 1);
            autoHandler.executeInput(sId, VALID_TOKEN, press);
            assertTrue(autoHandler.getPressedKeys().contains(65));

            RemoteInputCommand release =
                    new RemoteInputCommand(CommandType.KEY_RELEASE, 0, 0, 0, 65, 0, 2);
            autoHandler.executeInput(sId, VALID_TOKEN, release);
            assertFalse(autoHandler.getPressedKeys().contains(65),
                    "KEY_RELEASE must remove the key from the pressed set");
        } finally {
            UnicastRemoteObject.unexportObject(autoHandler, true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue 4: Operator Identity / Audit Log
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * SESSION_STARTED must be written to the audit log when consent is granted.
     */
    @Test
    void logsSessionStartedOnConsent() throws RemoteException {
        String sessionId = handler.requestAssistance("alice", VALID_TOKEN);
        assertFalse(sessionId.isEmpty(), "Session should be created");

        var entries = handler.getAuditLog().getEntries();
        assertTrue(entries.stream().anyMatch(e -> e.action().equals("SESSION_STARTED")),
                "Audit log must contain a SESSION_STARTED entry");
        assertTrue(entries.stream().anyMatch(e -> e.operatorId().equals("alice")),
                "Audit log must record the operator name");
    }

    /**
     * SCREEN_CAPTURE must be written to the audit log.
     */
    @Test
    void logsScreenCaptureAction() throws RemoteException {
        String sessionId = handler.requestAssistance("Operator", VALID_TOKEN);
        handler.captureScreen(sessionId, VALID_TOKEN);

        var entries = handler.getAuditLog().getEntries();
        assertTrue(entries.stream().anyMatch(e -> e.action().equals("SCREEN_CAPTURE")),
                "Audit log must contain a SCREEN_CAPTURE entry");
    }

    /**
     * INPUT_EXECUTED must be written to the audit log for every accepted command.
     */
    @Test
    void logsInputExecutedAction() throws RemoteException {
        ConsentClientHandler autoHandler = new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            String sId = autoHandler.requestAssistance("Operator", VALID_TOKEN);
            autoHandler.requestInputControl(sId, VALID_TOKEN);

            RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
            autoHandler.executeInput(sId, VALID_TOKEN, cmd);

            var entries = autoHandler.getAuditLog().getEntries();
            assertTrue(entries.stream().anyMatch(e -> e.action().equals("INPUT_EXECUTED")),
                    "Audit log must contain an INPUT_EXECUTED entry");
        } finally {
            UnicastRemoteObject.unexportObject(autoHandler, true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Issue 5: Session Expiry
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A session that has not expired must allow screen capture.
     */
    @Test
    void activeSessionAllowsCaptureBeforeExpiry() throws RemoteException {
        String sessionId = handler.requestAssistance("Operator", VALID_TOKEN);
        assertDoesNotThrow(() -> handler.captureScreen(sessionId, VALID_TOKEN),
                "Screen capture must succeed before expiry");
    }

    /**
     * Manipulating {@code sessionCreatedAt} to simulate time passage: if the session creation
     * time is set far enough in the past, the next call should be rejected with a
     * {@link RemoteException} indicating expiry.
     *
     * <p>This uses a reflection-free approach: we read the handler's {@code sessionCreatedAt}
     * via the exposed accessor and verify that the expiry logic would trigger when
     * {@code SESSION_TIMEOUT_MS} is exceeded. We simulate elapsed time by temporarily replacing
     * the handler with one whose timeout is 1 ms.
     */
    @Test
    void expiresSessionAfterTimeout() throws Exception {
        // Create a handler with a 1 ms session timeout to avoid real sleeping
        System.setProperty("activity.assistance.session-timeout-ms", "1");
        ConsentClientHandler shortTimeoutHandler =
                new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            String sId = shortTimeoutHandler.requestAssistance("Operator", VALID_TOKEN);

            // Confirm session is initially valid
            assertDoesNotThrow(() -> shortTimeoutHandler.captureScreen(sId, VALID_TOKEN));

            // Sleep just enough for the 1 ms timeout to expire
            Thread.sleep(10);

            // Next call must be rejected due to expiry
            assertThrows(RemoteException.class,
                    () -> shortTimeoutHandler.captureScreen(sId, VALID_TOKEN),
                    "captureScreen must throw after session timeout");
        } finally {
            System.clearProperty("activity.assistance.session-timeout-ms");
            UnicastRemoteObject.unexportObject(shortTimeoutHandler, true);
        }
    }

    /**
     * executeInput must also reject commands on an expired session.
     */
    @Test
    void expiresSessionForInputAfterTimeout() throws Exception {
        System.setProperty("activity.assistance.session-timeout-ms", "1");
        ConsentClientHandler shortTimeoutHandler =
                new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            String sId = shortTimeoutHandler.requestAssistance("Operator", VALID_TOKEN);
            shortTimeoutHandler.requestInputControl(sId, VALID_TOKEN);

            Thread.sleep(10);  // let the 1 ms timeout lapse

            RemoteInputCommand cmd = new RemoteInputCommand(CommandType.MOUSE_MOVE, 100, 100, 0, 0, 0, 1);
            assertThrows(RemoteException.class,
                    () -> shortTimeoutHandler.executeInput(sId, VALID_TOKEN, cmd),
                    "executeInput must throw after session timeout");
        } finally {
            System.clearProperty("activity.assistance.session-timeout-ms");
            UnicastRemoteObject.unexportObject(shortTimeoutHandler, true);
        }
    }

    /**
     * After expiry, the session state must be TERMINATED.
     */
    @Test
    void sessionStateIsTerminatedAfterExpiry() throws Exception {
        System.setProperty("activity.assistance.session-timeout-ms", "1");
        ConsentClientHandler shortTimeoutHandler =
                new ConsentClientHandler(op -> true, op -> true, VALID_TOKEN);
        try {
            String sId = shortTimeoutHandler.requestAssistance("Operator", VALID_TOKEN);
            Thread.sleep(10);

            try { shortTimeoutHandler.captureScreen(sId, VALID_TOKEN); } catch (RemoteException ignored) {}

            assertEquals(ConsentClientHandler.SessionState.TERMINATED, shortTimeoutHandler.getState(),
                    "State must be TERMINATED after expiry is detected");
        } finally {
            System.clearProperty("activity.assistance.session-timeout-ms");
            UnicastRemoteObject.unexportObject(shortTimeoutHandler, true);
        }
    }
}

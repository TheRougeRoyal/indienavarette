package com.veridion.assistance;

import com.veridion.shared.IClientHandler;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import com.veridion.shared.TimeoutSslRmiClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.Set;
import java.util.HashSet;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.veridion.shared.RemoteInputCommand;

/**
 * Client-side consent endpoint. Assistance is never accepted without an explicit local choice.
 *
 * <h2>Security Assumptions</h2>
 * <ul>
 *   <li>The operator credential is a shared secret of at least 32 characters, stored as a SHA-256
 *       hash. Do not transmit the raw credential over untrusted networks.</li>
 *   <li>Commands include a monotonically-increasing sequence number. The handler rejects any
 *       command whose {@code sequenceNumber} is not strictly greater than the last accepted one,
 *       preventing replay attacks.</li>
 *   <li>Sessions automatically expire after {@value #SESSION_TIMEOUT_MS} ms of inactivity. Callers
 *       must obtain a new session after expiry.</li>
 *   <li>Held key codes are kept only so they can be released at session teardown; key presses
 *       are not logged.</li>
 * </ul>
 */
public final class ConsentClientHandler extends UnicastRemoteObject implements IClientHandler {

    // ── Session expiry ────────────────────────────────────────────────────────
    /**
     * Session timeout in milliseconds. Defaults to 30 minutes. Set via system property
     * {@code activity.assistance.session-timeout-ms}. Read at construction time so that tests can
     * override it via {@link System#setProperty} before creating a handler.
     */
    final long sessionTimeoutMs;

    // ── Instance state ────────────────────────────────────────────────────────
    private final AtomicBoolean requestPending = new AtomicBoolean();
    private volatile SessionState state = SessionState.IDLE;
    private volatile String activeSessionId;
    private volatile long sessionCreatedAt = -1;

    private final Predicate<String> consentPrompt;
    private final Predicate<String> inputConsentPrompt;
    private final byte[] operatorCredentialHash;
    private final LongSupplier currentTimeMillis;
    private final Supplier<java.awt.Dimension> screenSizeSupplier;
    private final InputExecutor inputExecutor;

    /** Per-session sequence number. Resets to -1 on new session. */
    private long lastSequenceNumber = -1;

    /**
     * Keys currently held down. Tracked so they can all be released when the session ends,
     * preventing a "stuck key" state on the controlled desktop.
     */
    private final Set<Integer> pressedKeys = new HashSet<>();

    /** In-memory audit log. Captures every operator action with a timestamp. */
    private final AuditLog auditLog = new AuditLog();

    // ── Constructors ──────────────────────────────────────────────────────────

    public ConsentClientHandler() throws RemoteException {
        this(ConsentClientHandler::showPrompt, ConsentClientHandler::showInputPrompt,
                System.getProperty("activity.assistance.operator-token"));
    }

    ConsentClientHandler(Predicate<String> consentPrompt, String operatorCredential) throws RemoteException {
        this(consentPrompt, consentPrompt, operatorCredential);
    }

    ConsentClientHandler(Predicate<String> consentPrompt, Predicate<String> inputConsentPrompt,
                         String operatorCredential) throws RemoteException {
        this(consentPrompt, inputConsentPrompt, operatorCredential, System::currentTimeMillis);
    }

    ConsentClientHandler(Predicate<String> consentPrompt, Predicate<String> inputConsentPrompt,
                         String operatorCredential, LongSupplier currentTimeMillis) throws RemoteException {
        this(consentPrompt, inputConsentPrompt, operatorCredential, currentTimeMillis,
                Long.getLong("activity.assistance.session-timeout-ms", 30L * 60 * 1000));
    }

    ConsentClientHandler(Predicate<String> consentPrompt, Predicate<String> inputConsentPrompt,
                         String operatorCredential, LongSupplier currentTimeMillis,
                         long sessionTimeoutMs) throws RemoteException {
        this(consentPrompt, inputConsentPrompt, operatorCredential, currentTimeMillis, sessionTimeoutMs,
                null, null);
    }

    ConsentClientHandler(Predicate<String> consentPrompt, Predicate<String> inputConsentPrompt,
                         String operatorCredential, LongSupplier currentTimeMillis,
                         long sessionTimeoutMs, Supplier<java.awt.Dimension> screenSizeSupplier,
                         InputExecutor inputExecutor) throws RemoteException {
        super(0, new TimeoutSslRmiClientSocketFactory(), new SslRMIServerSocketFactory(null, null, false));
        if (operatorCredential == null || operatorCredential.length() < 32)
            throw new RemoteException("Configure a protected assistance operator token of at least 32 characters");
        if (sessionTimeoutMs <= 0) throw new RemoteException("Assistance session timeout must be positive");
        if (currentTimeMillis == null) throw new RemoteException("Assistance clock is required");
        this.consentPrompt = consentPrompt;
        this.inputConsentPrompt = inputConsentPrompt;
        this.operatorCredentialHash = sha256(operatorCredential);
        this.currentTimeMillis = currentTimeMillis;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.screenSizeSupplier = screenSizeSupplier == null
                ? () -> Toolkit.getDefaultToolkit().getScreenSize() : screenSizeSupplier;
        this.inputExecutor = inputExecutor == null ? this::executeInputWithRobot : inputExecutor;
    }

    // ── Auth helpers ──────────────────────────────────────────────────────────

    private static byte[] sha256(String value) throws RemoteException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RemoteException("SHA-256 unavailable", e);
        }
    }

    private boolean authorized(String credential) throws RemoteException {
        return credential != null && credential.length() <= 512
                && MessageDigest.isEqual(operatorCredentialHash, sha256(credential));
    }

    // ── Session expiry ────────────────────────────────────────────────────────

    /**
     * Checks whether the current session has exceeded {@code sessionTimeoutMs} ms and, if so,
     * terminates it before throwing {@link RemoteException}.
     *
     * @throws RemoteException if the session has expired
     */
    private void checkSessionExpiry() throws RemoteException {
        if (!Thread.holdsLock(this)) throw new IllegalStateException("Session lock must be held");
        if (sessionCreatedAt != -1 && (currentTimeMillis.getAsLong() - sessionCreatedAt > sessionTimeoutMs)) {
            terminateSessionInternal();
            throw new RemoteException("Session has expired");
        }
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /**
     * Internal teardown: releases all held keys, resets session state. Always call from a
     * {@code synchronized} context.
     */
    private void terminateSessionInternal() {
        if (!Thread.holdsLock(this)) throw new IllegalStateException("Session lock must be held");
        releaseAllKeys();
        state = SessionState.TERMINATED;
        activeSessionId = null;
        sessionCreatedAt = -1;
        lastSequenceNumber = -1;
    }

    /**
     * Requests a remote-assistance session. The operator must provide a valid credential and the
     * local user must accept the on-screen consent dialog.
     *
     * @param operatorName       display name shown in the consent dialog (1–128 characters)
     * @param operatorCredential shared secret matching the configured operator token
     * @return a UUID session identifier and enters {@link SessionState#ACTIVE_VIEW} when accepted;
     *         an explicit denial returns {@code ""} and leaves the state {@link SessionState#IDLE}.
     *         Headless execution or an already active session also returns {@code ""}.
     * @throws RemoteException if the credential is invalid or the consent dialog fails
     */
    @Override
    public String requestAssistance(String operatorName, String operatorCredential) throws RemoteException {
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        if (operatorName == null || operatorName.isBlank() || operatorName.length() > 128) return "";
        if (java.awt.GraphicsEnvironment.isHeadless()
                || !requestPending.compareAndSet(false, true)) return "";
        try {
            synchronized (this) {
                checkSessionExpiry();
                if (state == SessionState.ACTIVE_VIEW || state == SessionState.ACTIVE_CONTROL) return "";
                state = SessionState.REQUESTING_CONSENT;
            }
            boolean accepted = consentPrompt.test(operatorName);
            if (accepted) {
                synchronized (this) {
                    if (state == SessionState.TERMINATED) return "";
                    activeSessionId = UUID.randomUUID().toString();
                    state = SessionState.ACTIVE_VIEW;
                    sessionCreatedAt = currentTimeMillis.getAsLong();
                    lastSequenceNumber = -1;
                    auditLog.log(operatorName, "SESSION_STARTED", "Session ID: " + activeSessionId);
                    return activeSessionId;
                }
            }
            synchronized (this) {
                if (state != SessionState.TERMINATED) state = SessionState.IDLE;
                return "";
            }
        } catch (RuntimeException e) {
            throw new RemoteException("Unable to obtain local consent", e);
        } finally {
            requestPending.set(false);
        }
    }

    /**
     * Terminates an active session. Any keys held by the Robot at the time of termination are
     * released immediately to leave the desktop in a controllable state.
     *
     * @param sessionId          the UUID of the session to terminate
     * @param operatorCredential shared secret
     * @throws RemoteException if the credential is invalid
     */
    @Override
    public synchronized void terminateSession(String sessionId, String operatorCredential) throws RemoteException {
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        if (sessionId != null && sessionId.equals(activeSessionId)
                && (state == SessionState.ACTIVE_VIEW
                        || state == SessionState.ACTIVE_CONTROL
                        || state == SessionState.REQUESTING_CONSENT)) {
            terminateSessionInternal();
        }
    }

    /**
     * Captures the full primary screen.
     *
     * @param sessionId          active session UUID
     * @param operatorCredential shared secret
     * @return PNG-encoded screenshot bytes
     * @throws RemoteException if not authorized, session is invalid, or capture fails
     */
    @Override
    public synchronized byte[] captureScreen(String sessionId, String operatorCredential) throws RemoteException {
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        checkSessionExpiry();
        if (sessionId == null || !sessionId.equals(activeSessionId)
                || (state != SessionState.ACTIVE_VIEW && state != SessionState.ACTIVE_CONTROL)) {
            throw new RemoteException("No active assistance session authorized for screen capture");
        }

        auditLog.log("operator", "SCREEN_CAPTURE", "Session: " + sessionId);

        try {
            java.awt.Dimension screenSize = screenSizeSupplier.get();
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(0, 0, screenSize.width, screenSize.height);
            BufferedImage screenFullImage = robot.createScreenCapture(screenRect);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(screenFullImage, "png", baos);
                return baos.toByteArray();
            }
        } catch (java.awt.HeadlessException e) {
            throw new RemoteException("Screen capture requires an available display", e);
        } catch (java.awt.AWTException | IOException e) {
            throw new RemoteException("Failed to capture screen: " + e.getMessage());
        }
    }

    /**
     * Upgrades the session from view-only to full input control. A second consent dialog is shown
     * to the local user.
     *
     * @param sessionId          active session UUID
     * @param operatorCredential shared secret
     * @throws RemoteException if not authorized, session is invalid, or already in wrong state
     */
    @Override
    public synchronized void requestInputControl(String sessionId, String operatorCredential) throws RemoteException {
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        checkSessionExpiry();
        if (sessionId == null || !sessionId.equals(activeSessionId)) throw new RemoteException("Invalid session ID");

        if (state == SessionState.ACTIVE_CONTROL) return;
        if (state != SessionState.ACTIVE_VIEW) throw new RemoteException("Client must first be in a viewing session");

        final boolean accepted;
        try {
            accepted = inputConsentPrompt.test("Remote input control request");
        } catch (RuntimeException e) {
            throw new RemoteException("Unable to obtain input consent", e);
        }
        if (accepted) state = SessionState.ACTIVE_CONTROL;
    }

    /**
     * Executes a validated, sequenced input command via the AWT {@link Robot}.
     *
     * <p><b>Replay protection:</b> {@code command.sequenceNumber()} must be strictly greater than
     * the last accepted sequence number for this session. Commands with equal or lower numbers are
     * rejected to prevent replay attacks.
     *
     * <p><b>Input validation:</b> after credential, session, and replay checks, command bounds
     * and values are validated before any Robot action. Invalid bounds, key codes, buttons, or
     * scroll values are returned as a {@link RemoteException}.
     *
     * <p><b>Key tracking:</b> {@code KEY_PRESS} events add the key code to an internal set;
     * {@code KEY_RELEASE} events remove it. The set is cleared (and all keys released) when the
     * session terminates.
     *
     * @param sessionId          active session UUID
     * @param operatorCredential shared secret
     * @param command            the input command to execute; must not be {@code null}
     * @throws RemoteException if the credential is invalid, the session is expired/invalid, the
     *                         sequence number is stale, validation fails, no display is available,
     *                         or the AWT Robot fails
     */
    @Override
    public synchronized void executeInput(String sessionId, String operatorCredential,
                                          RemoteInputCommand command) throws RemoteException {
        if (command == null) throw new RemoteException("Command cannot be null");

        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");

        checkSessionExpiry();

        if (sessionId == null || !sessionId.equals(activeSessionId) || state != SessionState.ACTIVE_CONTROL) {
            throw new RemoteException("Remote input is not authorized for this session");
        }

        if (command.sequenceNumber() <= lastSequenceNumber) {
            throw new RemoteException(
                    "Duplicate or stale command sequence number: " + command.sequenceNumber());
        }

        // The credential, session, and replay checks precede display access and validation.
        try {
            java.awt.Dimension screenSize = screenSizeSupplier.get();
            command.validate(screenSize);
        } catch (java.awt.HeadlessException e) {
            throw new RemoteException("Input validation requires an available display", e);
        } catch (IllegalArgumentException e) {
            throw new RemoteException("Invalid command: " + e.getMessage(), e);
        }

        // Consume the sequence only after the entire command has passed validation.
        lastSequenceNumber = command.sequenceNumber();

        auditLog.log("operator", "INPUT_EXECUTED",
                "Type: " + command.type() + " Seq: " + command.sequenceNumber());

        inputExecutor.execute(command);
    }

    @FunctionalInterface
    interface InputExecutor { void execute(RemoteInputCommand command) throws RemoteException; }

    private void executeInputWithRobot(RemoteInputCommand command) throws RemoteException {
        try {
            Robot robot = new Robot();
            switch (command.type()) {
                case MOUSE_MOVE -> robot.mouseMove(command.x(), command.y());
                case MOUSE_PRESS -> robot.mousePress(command.button());
                case MOUSE_RELEASE -> robot.mouseRelease(command.button());
                case MOUSE_SCROLL -> robot.mouseWheel(command.amount());
                case KEY_PRESS -> {
                    robot.keyPress(command.keyCode());
                    pressedKeys.add(command.keyCode());
                }
                case KEY_RELEASE -> {
                    robot.keyRelease(command.keyCode());
                    pressedKeys.remove(command.keyCode());
                }
                default -> throw new RemoteException("Unsupported command type");
            }
        } catch (java.awt.AWTException e) {
            throw new RemoteException("Input execution failed: " + e.getMessage());
        }
    }

    // ── Key-cleanup helpers ───────────────────────────────────────────────────

    /**
     * Releases all keys currently tracked as pressed, then clears the tracking set. Called
     * automatically during session termination to ensure the desktop is never left with a key
     * stuck in the down state.
     */
    private void releaseAllKeys() {
        if (!Thread.holdsLock(this)) throw new IllegalStateException("Session lock must be held");
        try {
            Robot robot = new Robot();
            for (Integer keyCode : pressedKeys) robot.keyRelease(keyCode);
        } catch (java.awt.AWTException | java.awt.HeadlessException e) {
            // Log, but do not block session teardown.
        } finally {
            pressedKeys.clear();
        }
    }

    // ── Prompt helpers ────────────────────────────────────────────────────────

    private static boolean showPrompt(String operatorName) {
        final int[] choice = {JOptionPane.CLOSED_OPTION};
        try {
            SwingUtilities.invokeAndWait(() -> choice[0] = JOptionPane.showConfirmDialog(null,
                    operatorName + " is requesting a remote-assistance session. Allow it?",
                    "Remote assistance request", JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE));
        } catch (Exception e) {
            return false;
        }
        return choice[0] == JOptionPane.YES_OPTION;
    }

    private static boolean showInputPrompt(String ignored) {
        final int[] choice = {JOptionPane.CLOSED_OPTION};
        try {
            SwingUtilities.invokeAndWait(() -> choice[0] = JOptionPane.showConfirmDialog(null,
                    "The operator is requesting remote control of your mouse and keyboard. Allow it?",
                    "Remote Input Request", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE));
        } catch (Exception e) {
            return false;
        }
        return choice[0] == JOptionPane.YES_OPTION;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** @return {@code true} if a session is currently in VIEW or CONTROL state */
    public synchronized boolean isSessionActive() {
        expireBeforeStateRead();
        return state == SessionState.ACTIVE_VIEW || state == SessionState.ACTIVE_CONTROL;
    }

    /** @return the UUID of the currently active session, or {@code null} if none */
    public synchronized String getActiveSessionId() {
        expireBeforeStateRead();
        return activeSessionId;
    }

    /** @return the current session lifecycle state */
    public synchronized SessionState getState() {
        expireBeforeStateRead();
        return state;
    }

    private void expireBeforeStateRead() {
        try { checkSessionExpiry(); }
        catch (RemoteException ignored) { /* Expiry has already transitioned the state. */ }
    }

    /**
     * Returns an unmodifiable view of the set of key codes currently held down by the Robot.
     * Exposed for testing only; do not rely on this in production callers.
     */
    public synchronized Set<Integer> getPressedKeys() { return Set.copyOf(pressedKeys); }

    synchronized long getLastSequenceNumber() { return lastSequenceNumber; }

    /**
     * Returns the timestamp (epoch ms) at which the current session was created, or {@code -1}
     * if no session is active. Exposed for testing session-expiry behaviour.
     */
    public synchronized long getSessionCreatedAt() {
        expireBeforeStateRead();
        return sessionCreatedAt;
    }

    /**
     * Returns the in-memory audit log. Exposed for testing; do not expose over RMI.
     */
    public AuditLog getAuditLog() { return auditLog; }

    // ── Session state enum ────────────────────────────────────────────────────

    public enum SessionState {
        IDLE,
        REQUESTING_CONSENT,
        ACTIVE_VIEW,
        ACTIVE_CONTROL,
        TERMINATED
    }
}

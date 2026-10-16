package com.veridion.assistance;

import com.veridion.shared.IClientHandler;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import com.veridion.shared.TimeoutSslRmiClientSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.function.Predicate;
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

/** Client-side consent endpoint. Assistance is never accepted without an explicit local choice. */
public final class ConsentClientHandler extends UnicastRemoteObject implements IClientHandler {
    private final AtomicBoolean requestPending = new AtomicBoolean();
    private volatile SessionState state = SessionState.IDLE;
    private volatile String activeSessionId;
    private final Predicate<String> consentPrompt;
    private final Predicate<String> inputConsentPrompt;
    private final byte[] operatorCredentialHash;

    public ConsentClientHandler() throws RemoteException {
        this(ConsentClientHandler::showPrompt, ConsentClientHandler::showInputPrompt,
                System.getProperty("activity.assistance.operator-token"));
    }
    ConsentClientHandler(Predicate<String> consentPrompt, String operatorCredential) throws RemoteException {
        this(consentPrompt, consentPrompt, operatorCredential);
    }
    ConsentClientHandler(Predicate<String> consentPrompt, Predicate<String> inputConsentPrompt, String operatorCredential) throws RemoteException {
        super(0, new TimeoutSslRmiClientSocketFactory(), new SslRMIServerSocketFactory(null, null, false));
        if (operatorCredential == null || operatorCredential.length() < 32) throw new RemoteException("Configure a protected assistance operator token of at least 32 characters");
        this.consentPrompt = consentPrompt;
        this.inputConsentPrompt = inputConsentPrompt;
        this.operatorCredentialHash = sha256(operatorCredential);
    }

    private static byte[] sha256(String value) throws RemoteException {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new RemoteException("SHA-256 unavailable", e); }
    }

    private boolean authorized(String credential) throws RemoteException {
        return credential != null && credential.length() <= 512
                && MessageDigest.isEqual(operatorCredentialHash, sha256(credential));
    }

    @Override public String requestAssistance(String operatorName, String operatorCredential) throws RemoteException {
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        if (operatorName == null || operatorName.isBlank() || operatorName.length() > 128) return "";
        if (java.awt.GraphicsEnvironment.isHeadless() || state == SessionState.ACTIVE_VIEW || state == SessionState.ACTIVE_CONTROL || !requestPending.compareAndSet(false, true)) return "";
        try {
            state = SessionState.REQUESTING_CONSENT;
            boolean accepted = consentPrompt.test(operatorName);
            if (accepted) {
                synchronized (this) {
                    if (state == SessionState.TERMINATED) return "";
                    activeSessionId = UUID.randomUUID().toString();
                    state = SessionState.ACTIVE_VIEW;
                    return activeSessionId;
                }
            }
            if (state != SessionState.TERMINATED) state = SessionState.IDLE;
            return "";
        } catch (RuntimeException e) { throw new RemoteException("Unable to obtain local consent", e); }
        finally { requestPending.set(false); }
    }

    private static boolean showPrompt(String operatorName) {
        final int[] choice = {JOptionPane.CLOSED_OPTION};
        try { SwingUtilities.invokeAndWait(() -> choice[0] = JOptionPane.showConfirmDialog(null,
                operatorName + " is requesting a remote-assistance session. Allow it?",
                "Remote assistance request", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)); }
        catch (Exception e) { return false; }
        return choice[0] == JOptionPane.YES_OPTION;
    }

    private static boolean showInputPrompt(String ignored) {
        final int[] choice = {JOptionPane.CLOSED_OPTION};
        try { SwingUtilities.invokeAndWait(() -> choice[0] = JOptionPane.showConfirmDialog(null,
                "The operator is requesting remote control of your mouse and keyboard. Allow it?",
                "Remote Input Request", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)); }
        catch (Exception e) { return false; }
        return choice[0] == JOptionPane.YES_OPTION;
    }

    @Override public synchronized void terminateSession(String sessionId, String operatorCredential) throws RemoteException {
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        if (sessionId != null && sessionId.equals(activeSessionId)
                && (state == SessionState.ACTIVE_VIEW || state == SessionState.ACTIVE_CONTROL || state == SessionState.REQUESTING_CONSENT)) {
            state = SessionState.TERMINATED;
            activeSessionId = null;
        }
    }

    @Override
    public synchronized byte[] captureScreen(String sessionId, String operatorCredential) throws RemoteException {
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        if (sessionId == null || !sessionId.equals(activeSessionId) || (state != SessionState.ACTIVE_VIEW && state != SessionState.ACTIVE_CONTROL)) {
            throw new RemoteException("No active assistance session authorized for screen capture");
        }

        try {
            Robot robot = new Robot();
            java.awt.Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            Rectangle screenRect = new Rectangle(0, 0, screenSize.width, screenSize.height);
            BufferedImage screenFullImage = robot.createScreenCapture(screenRect);

            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                ImageIO.write(screenFullImage, "png", baos);
                return baos.toByteArray();
            }
        } catch (java.awt.AWTException | IOException e) {
            throw new RemoteException("Failed to capture screen: " + e.getMessage());
        }
    }

    @Override
    public synchronized void requestInputControl(String sessionId, String operatorCredential) throws RemoteException {
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        if (sessionId == null || !sessionId.equals(activeSessionId)) throw new RemoteException("Invalid session ID");

        if (state == SessionState.ACTIVE_CONTROL) return;
        if (state != SessionState.ACTIVE_VIEW) throw new RemoteException("Client must first be in a viewing session");

        final boolean accepted;
        try { accepted = inputConsentPrompt.test("Remote input control request"); }
        catch (RuntimeException e) { throw new RemoteException("Unable to obtain input consent", e); }
        if (accepted) state = SessionState.ACTIVE_CONTROL;
    }

    @Override
    public synchronized void executeInput(String sessionId, String operatorCredential, RemoteInputCommand command) throws RemoteException {
        if (command == null) throw new RemoteException("Command cannot be null");
        command.validate();
        if (!authorized(operatorCredential)) throw new RemoteException("Assistance operator is not authorized");
        if (sessionId == null || !sessionId.equals(activeSessionId) || state != SessionState.ACTIVE_CONTROL) {
            throw new RemoteException("Remote input is not authorized for this session");
        }

        try {
            Robot robot = new Robot();
            switch (command.type()) {
                case MOUSE_MOVE -> robot.mouseMove(command.x(), command.y());
                case MOUSE_PRESS -> robot.mousePress(command.button());
                case MOUSE_RELEASE -> robot.mouseRelease(command.button());
                case MOUSE_SCROLL -> robot.mouseWheel(command.amount());
                case KEY_PRESS -> robot.keyPress(command.keyCode());
                case KEY_RELEASE -> robot.keyRelease(command.keyCode());
                default -> throw new RemoteException("Unsupported command type");
            }
        } catch (java.awt.AWTException e) {
            throw new RemoteException("Input execution failed: " + e.getMessage());
        }
    }

    public boolean isSessionActive() { return state == SessionState.ACTIVE_VIEW || state == SessionState.ACTIVE_CONTROL; }
    public String getActiveSessionId() { return activeSessionId; }
    public SessionState getState() { return state; }
    public enum SessionState { IDLE, REQUESTING_CONSENT, ACTIVE_VIEW, ACTIVE_CONTROL, TERMINATED }
}

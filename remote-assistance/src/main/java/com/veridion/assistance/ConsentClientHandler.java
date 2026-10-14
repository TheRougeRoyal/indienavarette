package com.veridion.assistance;

import com.veridion.shared.IClientHandler;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Client-side consent endpoint. Assistance is never accepted without an explicit local choice. */
public final class ConsentClientHandler extends UnicastRemoteObject implements IClientHandler {
    private final AtomicBoolean sessionActive = new AtomicBoolean();

    public ConsentClientHandler() throws RemoteException { super(); }

    @Override public boolean requestAssistance(String operatorName) throws RemoteException {
        if (operatorName == null || operatorName.isBlank() || operatorName.length() > 128) return false;
        final int[] choice = {JOptionPane.CLOSED_OPTION};
        try {
            SwingUtilities.invokeAndWait(() -> choice[0] = JOptionPane.showConfirmDialog(null,
                    operatorName + " is requesting a remote-assistance session. Allow it?",
                    "Remote assistance request", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE));
        } catch (Exception e) { throw new RemoteException("Unable to obtain local consent", e); }
        boolean accepted = choice[0] == JOptionPane.YES_OPTION;
        sessionActive.set(accepted);
        return accepted;
    }

    @Override public void terminateSession() { sessionActive.set(false); }

    public boolean isSessionActive() { return sessionActive.get(); }
}

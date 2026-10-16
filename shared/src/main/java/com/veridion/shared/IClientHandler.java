package com.veridion.shared;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IClientHandler extends Remote {
    String requestAssistance(String operatorName, String operatorCredential) throws RemoteException;
    void requestInputControl(String sessionId, String operatorCredential) throws RemoteException;
    void terminateSession(String sessionId, String operatorCredential) throws RemoteException;
    byte[] captureScreen(String sessionId, String operatorCredential) throws RemoteException;
    void executeInput(String sessionId, String operatorCredential, RemoteInputCommand command) throws RemoteException;
}

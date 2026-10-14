package com.veridion.shared;

import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IClientHandler extends Remote {
    String requestAssistance(String operatorName, String operatorCredential) throws RemoteException;
    void terminateSession(String sessionId, String operatorCredential) throws RemoteException;
}

package com.veridion.shared;

import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IClientHandler extends Remote {
    boolean requestAssistance(String operatorName) throws RemoteException;
    void terminateSession() throws RemoteException;
}

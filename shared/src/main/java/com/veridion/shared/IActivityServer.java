package com.veridion.shared;

import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IActivityServer extends Remote {
    void registerClient(String clientId, String hostname) throws RemoteException;
    void reportActivity(ActivityEvent event) throws RemoteException;
    void sendHeartbeat(String clientId) throws RemoteException;
}

package com.veridion.shared;

import java.io.Serializable;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface IActivityServer extends Remote {
    String registerClient(String clientId, String hostname, String enrollmentToken) throws RemoteException;
    void reportActivity(ActivityEvent event, String clientCredential) throws RemoteException;
    void sendHeartbeat(String clientId, String clientCredential) throws RemoteException;
}

package com.veridion.server;

import com.veridion.shared.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

public class ActivityServerImpl extends UnicastRemoteObject implements IActivityServer {
    private final ConcurrentHashMap<String, ClientSession> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, NavigableMap<LocalDateTime, ActivityEvent>> activityLogs = new ConcurrentHashMap<>();

    protected ActivityServerImpl() throws RemoteException {
        super();
    }

    @Override
    public void registerClient(String clientId, String hostname) throws RemoteException {
        System.out.println("Registering client: " + clientId + " [" + hostname + "]");
        clients.put(clientId, new ClientSession(clientId, hostname));
        activityLogs.put(clientId, new ConcurrentSkipListMap<>());
    }

    @Override
    public void reportActivity(ActivityEvent event) throws RemoteException {
        System.out.println("Activity from " + event.clientId() + ": " + event.appName());
        activityLogs.get(event.clientId()).put(event.timestamp(), event);
    }

    @Override
    public void sendHeartbeat(String clientId) throws RemoteException {
        ClientSession session = clients.get(clientId);
        if (session != null) {
            session.updateHeartbeat();
        }
    }

    public Map<String, ClientSession> getClients() {
        return Collections.unmodifiableMap(clients);
    }

    public NavigableMap<LocalDateTime, ActivityEvent> getLogsForClient(String clientId) {
        return activityLogs.get(clientId);
    }

    public static class ClientSession {
        private final String id;
        private final String hostname;
        private LocalDateTime lastSeen;

        public ClientSession(String id, String hostname) {
            this.id = id;
            this.hostname = hostname;
            this.lastSeen = LocalDateTime.now();
        }

        public void updateHeartbeat() {
            this.lastSeen = LocalDateTime.now();
        }

        public String getId() { return id; }
        public String getHostname() { return hostname; }
        public LocalDateTime getLastSeen() { return lastSeen; }
    }
}

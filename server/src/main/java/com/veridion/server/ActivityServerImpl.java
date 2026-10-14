package com.veridion.server;

import com.veridion.shared.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

public class ActivityServerImpl extends UnicastRemoteObject implements IActivityServer {
    private final ConcurrentHashMap<String, ClientSession> clients = new ConcurrentHashMap<>();
    private static final int MAX_EVENTS_PER_CLIENT = 5000;
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 30;
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<LocalDateTime, ActivityEvent>> activityLogs = new ConcurrentHashMap<>();

    protected ActivityServerImpl() throws RemoteException {
        super();
    }

    @Override
    public void registerClient(String clientId, String hostname) throws RemoteException {
        if (clientId == null || clientId.isBlank() || clientId.length() > 128 || hostname == null || hostname.isBlank() || hostname.length() > 255)
            throw new RemoteException("Invalid client registration");
        System.out.println("Registering client: " + clientId + " [" + hostname + "]");
        clients.put(clientId, new ClientSession(clientId, hostname));
        activityLogs.computeIfAbsent(clientId, ignored -> new ConcurrentSkipListMap<>());
    }

    @Override
    public void reportActivity(ActivityEvent event) throws RemoteException {
        if (event == null || event.clientId() == null || event.timestamp() == null || event.appName() == null || event.windowTitle() == null
                || event.appName().length() > 512 || event.windowTitle().length() > 2048)
            throw new RemoteException("Invalid activity report");
        if (!clients.containsKey(event.clientId())) throw new RemoteException("Client is not registered");
        var logs = activityLogs.get(event.clientId());
        synchronized (logs) {
            LocalDateTime key = event.timestamp();
            while (logs.containsKey(key)) key = key.plusNanos(1);
            logs.put(key, event);
            while (logs.size() > MAX_EVENTS_PER_CLIENT) logs.pollFirstEntry();
        }
    }

    @Override
    public void sendHeartbeat(String clientId) throws RemoteException {
        ClientSession session = clients.get(clientId);
        if (session == null) throw new RemoteException("Client is not registered");
        session.updateHeartbeat();
    }

    public Map<String, ClientSession> getClients() {
        return Collections.unmodifiableMap(new HashMap<>(clients));
    }

    public NavigableMap<LocalDateTime, ActivityEvent> getLogsForClient(String clientId) {
        var logs = activityLogs.get(clientId);
        if (logs == null) return null;
        synchronized (logs) { return Collections.unmodifiableNavigableMap(new TreeMap<>(logs)); }
    }

    public boolean isConnected(ClientSession session) { return session != null && session.getLastSeen().isAfter(LocalDateTime.now().minusSeconds(HEARTBEAT_TIMEOUT_SECONDS)); }

    public static class ClientSession {
        private final String id;
        private final String hostname;
        private volatile LocalDateTime lastSeen;

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

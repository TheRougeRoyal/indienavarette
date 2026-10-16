package com.veridion.server;

import com.veridion.shared.*;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import com.veridion.shared.TimeoutSslRmiClientSocketFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.Properties;
import java.nio.file.Files;
import java.nio.file.Path;

public class ActivityServerImpl extends UnicastRemoteObject implements IActivityServer {
    private final ConcurrentHashMap<String, ClientSession> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> clientCredentials = new ConcurrentHashMap<>();
    private final Map<String, byte[]> enrollmentTokenHashes;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_EVENTS_PER_CLIENT = 5000;
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 30;
    private final Duration heartbeatTimeout;
    private final ConcurrentHashMap<String, ConcurrentSkipListMap<LocalDateTime, ActivityEvent>> activityLogs = new ConcurrentHashMap<>();

    protected ActivityServerImpl() throws RemoteException {
        this(requiredEnrollmentTokens(), RmiConfig.objectPort(), Duration.ofSeconds(HEARTBEAT_TIMEOUT_SECONDS));
    }

    ActivityServerImpl(String enrollmentToken) throws RemoteException { this(Map.of("c1", enrollmentToken), 0, Duration.ofSeconds(HEARTBEAT_TIMEOUT_SECONDS)); }

    ActivityServerImpl(String enrollmentToken, int objectPort) throws RemoteException { this(Map.of("c1", enrollmentToken), objectPort, Duration.ofSeconds(HEARTBEAT_TIMEOUT_SECONDS)); }

    ActivityServerImpl(String enrollmentToken, Duration heartbeatTimeout) throws RemoteException { this(Map.of("c1", enrollmentToken), 0, heartbeatTimeout); }

    protected ActivityServerImpl(Map<String, String> enrollmentTokens, int objectPort, Duration heartbeatTimeout) throws RemoteException {
        super(objectPort, new TimeoutSslRmiClientSocketFactory(), new SslRMIServerSocketFactory(null, null, false));
        if (enrollmentTokens == null || enrollmentTokens.isEmpty()) throw new RemoteException("No client enrollment credentials configured");
        if (heartbeatTimeout == null || heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) throw new RemoteException("Heartbeat timeout must be positive");
        Map<String, byte[]> hashes = new HashMap<>();
        for (var entry : enrollmentTokens.entrySet()) {
            String id = entry.getKey();
            String token = entry.getValue();
            if (id == null || id.isBlank() || id.length() > 128 || token == null || token.length() < 32)
                throw new IllegalArgumentException("Each enrollment entry requires a client ID and token of at least 32 characters");
            hashes.put(id, sha256(token));
        }
        this.enrollmentTokenHashes = Map.copyOf(hashes);
        this.heartbeatTimeout = heartbeatTimeout;
    }

    private static Map<String, String> requiredEnrollmentTokens() throws RemoteException {
        String filename = System.getProperty("activity.enrollment.file");
        if (filename == null || filename.isBlank()) throw new RemoteException("Set activity.enrollment.file to a protected clientId=token properties file");
        Properties values = new Properties();
        try (var input = Files.newInputStream(Path.of(filename))) { values.load(input); }
        catch (Exception e) { throw new RemoteException("Unable to read enrollment credential file", e); }
        Map<String, String> result = new HashMap<>();
        values.stringPropertyNames().forEach(id -> result.put(id, values.getProperty(id)));
        if (result.isEmpty()) throw new RemoteException("Enrollment credential file contains no clients");
        return result;
    }

    private static byte[] sha256(String value) throws RemoteException {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new RemoteException("SHA-256 unavailable", e); }
    }

    @Override
    public String registerClient(String clientId, String hostname, String enrollmentToken) throws RemoteException {
        if (clientId == null || clientId.isBlank() || clientId.length() > 128 || hostname == null || hostname.isBlank() || hostname.length() > 255)
            throw new RemoteException("Invalid client registration parameters");
        byte[] expectedHash = enrollmentTokenHashes.get(clientId);
        if (enrollmentToken == null || enrollmentToken.length() > 512 || expectedHash == null
                || !MessageDigest.isEqual(expectedHash, sha256(enrollmentToken)))
            throw new RemoteException("Client enrollment rejected");

        // ponytail: invalidate old session on re-registration
        clientCredentials.remove(clientId);

        System.out.println("Registering client: " + clientId + " [" + hostname + "]");
        clients.put(clientId, new ClientSession(clientId, hostname));
        activityLogs.computeIfAbsent(clientId, ignored -> new ConcurrentSkipListMap<>());
        byte[] secret = new byte[32]; RANDOM.nextBytes(secret);
        String credential = HexFormat.of().formatHex(secret);
        clientCredentials.put(clientId, credential);
        return credential;
    }

    @Override
    public void reportActivity(ActivityEvent event, String credential) throws RemoteException {
        if (event == null || event.clientId() == null || event.timestamp() == null || event.appName() == null || event.windowTitle() == null
                || event.appName().length() > 512 || event.windowTitle().length() > 2048)
            throw new RemoteException("Invalid activity report");
        authorize(event.clientId(), credential);
        var logs = activityLogs.get(event.clientId());
        synchronized (logs) {
            LocalDateTime key = event.timestamp();
            while (logs.containsKey(key)) key = key.plusNanos(1);
            logs.put(key, event);
            while (logs.size() > MAX_EVENTS_PER_CLIENT) logs.pollFirstEntry();
        }
    }

    @Override
    public void sendHeartbeat(String clientId, String credential) throws RemoteException {
        ClientSession session = clients.get(clientId);
        authorize(clientId, credential);
        if (session == null) throw new RemoteException("Client is not registered");
        session.updateHeartbeat();
    }

    private void authorize(String clientId, String credential) throws RemoteException {
        String expected = clientCredentials.get(clientId);
        if (expected == null || credential == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), credential.getBytes(StandardCharsets.UTF_8)))
            throw new RemoteException("Client is not authorized");
    }

    public Map<String, ClientSession> getClients() {
        return Collections.unmodifiableMap(new HashMap<>(clients));
    }

    public NavigableMap<LocalDateTime, ActivityEvent> getLogsForClient(String clientId) {
        var logs = activityLogs.get(clientId);
        if (logs == null) return null;
        synchronized (logs) { return Collections.unmodifiableNavigableMap(new TreeMap<>(logs)); }
    }

    public boolean isConnected(ClientSession session) { return session != null && session.getLastSeen().isAfter(LocalDateTime.now().minus(heartbeatTimeout)); }

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

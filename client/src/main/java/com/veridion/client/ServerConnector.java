package com.veridion.client;

import com.veridion.shared.IActivityServer;
import com.veridion.shared.RmiConfig;
import com.veridion.shared.TimeoutSslRmiClientSocketFactory;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

@FunctionalInterface
interface ServerConnector {
    Connection enroll(String host, int port, String clientId, String enrollmentToken) throws Exception;

    @FunctionalInterface
    interface HostnameLookup { String get() throws Exception; }

    static String resolveHostname(HostnameLookup lookup) {
        try {
            String hostname = lookup.get();
            if (hostname != null && !hostname.isBlank()) return hostname;
        } catch (Exception ignored) { /* Use the configured fallback below. */ }
        String fallback = System.getProperty("activity.client.hostname", "unknown");
        return fallback == null || fallback.isBlank() ? "unknown" : fallback;
    }

    record Connection(IActivityServer server, String credential) { }

    static ServerConnector rmi() {
        return (host, port, clientId, enrollmentToken) -> {
            Registry registry = LocateRegistry.getRegistry(host, port, new TimeoutSslRmiClientSocketFactory());
            IActivityServer server = (IActivityServer) registry.lookup(RmiConfig.BINDING);
            String credential = server.registerClient(clientId,
                    resolveHostname(() -> java.net.InetAddress.getLocalHost().getHostName()), enrollmentToken);
            return new Connection(server, credential);
        };
    }
}

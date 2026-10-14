package com.veridion.client;

import com.veridion.shared.IActivityServer;
import com.veridion.shared.RmiConfig;
import com.veridion.shared.TimeoutSslRmiClientSocketFactory;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

@FunctionalInterface
interface ServerConnector {
    Connection enroll(String host, int port, String clientId, String enrollmentToken) throws Exception;

    record Connection(IActivityServer server, String credential) { }

    static ServerConnector rmi() {
        return (host, port, clientId, enrollmentToken) -> {
            Registry registry = LocateRegistry.getRegistry(host, port, new TimeoutSslRmiClientSocketFactory());
            IActivityServer server = (IActivityServer) registry.lookup(RmiConfig.BINDING);
            String credential = server.registerClient(clientId, java.net.InetAddress.getLocalHost().getHostName(), enrollmentToken);
            return new Connection(server, credential);
        };
    }
}

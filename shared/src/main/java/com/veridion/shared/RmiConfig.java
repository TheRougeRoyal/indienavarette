package com.veridion.shared;

/** Shared RMI endpoint configuration for server and clients. */
public final class RmiConfig {
    public static final int DEFAULT_PORT = 1099;
    public static final String PORT_PROPERTY = "activity.server.port";
    public static final String OBJECT_PORT_PROPERTY = "activity.rmi.object.port";
    public static final String BINDING = "ActivityServer";

    private RmiConfig() { }

    public static int port() {
        int port = configuredInt(PORT_PROPERTY, DEFAULT_PORT);
        return port;
    }

    public static int objectPort() {
        int registryPort = port();
        return configuredInt(OBJECT_PORT_PROPERTY, registryPort == 65535 ? 1 : registryPort + 1);
    }

    private static int configuredInt(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) throw new IllegalArgumentException(name + " must be 1..65535");
            return port;
        } catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be an integer port", e); }
    }
}

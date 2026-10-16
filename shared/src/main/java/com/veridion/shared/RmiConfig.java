package com.veridion.shared;

/** Shared RMI endpoint configuration for server and clients. */
public final class RmiConfig {
    public static final int DEFAULT_PORT = 1099;
    public static final String PORT_PROPERTY = "activity.server.port";
    public static final String BINDING = "ActivityServer";

    private RmiConfig() { }

    public static int port() {
        int port = configuredInt(PORT_PROPERTY, DEFAULT_PORT);
        return port;
    }

    public static int objectPort() {
        // RMI shares its transport between the registry and exported objects when
        // they use equivalent socket factories, so one firewall port is sufficient.
        return port();
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

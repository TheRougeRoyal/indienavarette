package com.veridion.client;

import com.veridion.shared.RmiConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RmiConfigPortTest {
    @Test void registryAndObjectPortsShareConfiguration() {
        String oldRegistry = System.getProperty(RmiConfig.PORT_PROPERTY);
        try {
            System.setProperty(RmiConfig.PORT_PROPERTY, "23001");
            assertEquals(23001, RmiConfig.port());
            assertEquals(23001, RmiConfig.objectPort());
            System.setProperty(RmiConfig.PORT_PROPERTY, "not-a-port");
            assertThrows(IllegalArgumentException.class, RmiConfig::port);
        } finally {
            restore(RmiConfig.PORT_PROPERTY, oldRegistry);
        }
    }
    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}

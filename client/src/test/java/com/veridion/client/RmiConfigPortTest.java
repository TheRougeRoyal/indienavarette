package com.veridion.client;

import com.veridion.shared.RmiConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RmiConfigPortTest {
    @Test void registryAndObjectPortsShareConfiguration() {
        String oldRegistry = System.getProperty(RmiConfig.PORT_PROPERTY);
        String oldObject = System.getProperty(RmiConfig.OBJECT_PORT_PROPERTY);
        try {
            System.setProperty(RmiConfig.PORT_PROPERTY, "23001");
            System.clearProperty(RmiConfig.OBJECT_PORT_PROPERTY);
            assertEquals(23001, RmiConfig.port());
            assertEquals(23002, RmiConfig.objectPort());
            System.setProperty(RmiConfig.OBJECT_PORT_PROPERTY, "23003");
            assertEquals(23003, RmiConfig.objectPort());
            System.setProperty(RmiConfig.PORT_PROPERTY, "not-a-port");
            assertThrows(IllegalArgumentException.class, RmiConfig::port);
        } finally {
            restore(RmiConfig.PORT_PROPERTY, oldRegistry);
            restore(RmiConfig.OBJECT_PORT_PROPERTY, oldObject);
        }
    }
    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}

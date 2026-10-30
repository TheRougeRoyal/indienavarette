package com.veridion.client;

import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerConnectorTest {
    @Test void hostnameLookupFailureUsesConfiguredFallback() {
        String previous = System.getProperty("activity.client.hostname");
        System.setProperty("activity.client.hostname", "workstation-01");
        try {
            assertEquals("workstation-01", ServerConnector.resolveHostname(() -> {
                throw new UnknownHostException("test failure");
            }));
        } finally {
            if (previous == null) System.clearProperty("activity.client.hostname");
            else System.setProperty("activity.client.hostname", previous);
        }
    }

    @Test void blankHostnameFallbackDefaultsToUnknown() {
        String previous = System.getProperty("activity.client.hostname");
        System.setProperty("activity.client.hostname", " ");
        try {
            assertEquals("unknown", ServerConnector.resolveHostname(() -> ""));
        } finally {
            if (previous == null) System.clearProperty("activity.client.hostname");
            else System.setProperty("activity.client.hostname", previous);
        }
    }
}

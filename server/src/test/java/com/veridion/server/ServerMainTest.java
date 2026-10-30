package com.veridion.server;

import com.veridion.shared.ActivityEvent;
import org.junit.jupiter.api.Test;

import java.io.ObjectInputFilter;
import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerMainTest {
    @Test void serializationFilterAllowsWireDtoAndRejectsUnlistedTypes() {
        ObjectInputFilter filter = ServerMain.serializationFilter();
        assertEquals(ObjectInputFilter.Status.ALLOWED, filter.checkInput(info(ActivityEvent.class)));
        assertEquals(ObjectInputFilter.Status.REJECTED, filter.checkInput(info(ArrayList.class)));
    }

    private static ObjectInputFilter.FilterInfo info(Class<?> type) {
        return new ObjectInputFilter.FilterInfo() {
            @Override public Class<?> serialClass() { return type; }
            @Override public long arrayLength() { return -1; }
            @Override public long depth() { return 1; }
            @Override public long references() { return 1; }
            @Override public long streamBytes() { return 64; }
        };
    }
}

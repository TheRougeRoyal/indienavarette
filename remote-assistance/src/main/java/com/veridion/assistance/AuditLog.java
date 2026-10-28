package com.veridion.assistance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AuditLog {
    public record AuditEntry(String operatorId, String action, String details, long timestamp) {}

    private final List<AuditEntry> entries = Collections.synchronizedList(new ArrayList<>());

    public void log(String operatorId, String action, String details) {
        entries.add(new AuditEntry(operatorId, action, details, System.currentTimeMillis()));
    }

    public List<AuditEntry> getEntries() {
        return new ArrayList<>(entries);
    }
}

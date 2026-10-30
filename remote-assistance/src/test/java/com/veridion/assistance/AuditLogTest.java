package com.veridion.assistance;

import com.veridion.shared.RemoteInputCommand;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {
    private static final String TOKEN = "test-operator-token-long-enough-0123456789";

    @Test
    void entriesPersistAndEscapedFieldsRemainOneRecord() throws Exception {
        Path file = Files.createTempDirectory("assistance-audit").resolve("events.log");
        try (AuditLog first = new AuditLog(file)) {
            first.log("operator\tname", "ACTION", "session", "line one\nline\ttwo");
        }
        assertEquals(1, Files.readAllLines(file).size());
        try (AuditLog reopened = new AuditLog(file)) {
            List<AuditLog.AuditEntry> entries = reopened.getEntries();
            assertEquals(1, entries.size());
            assertEquals("operator\tname", entries.getFirst().operatorId());
            assertEquals("line one\nline\ttwo", entries.getFirst().details());
            assertEquals("session", entries.getFirst().sessionId());
        }
    }

    @Test
    void failedAuditWriteRefusesCaptureAndInputActions() throws Exception {
        AtomicInteger screenLookups = new AtomicInteger();
        AtomicInteger actions = new AtomicInteger();
        AuditLog audit = new AuditLog();
        ConsentClientHandler handler = new ConsentClientHandler(op -> true, op -> true, TOKEN,
                System::currentTimeMillis, 60_000, () -> {
                    screenLookups.incrementAndGet();
                    return new Dimension(1920, 1080);
                }, command -> actions.incrementAndGet(), audit);
        String session = handler.requestAssistance("Operator", TOKEN);
        handler.requestInputControl(session, TOKEN);

        // Closing the sink models a failed append after a session has already been authorized.
        audit.close();
        assertThrows(RemoteException.class, () -> handler.captureScreen(session, TOKEN));
        assertEquals(0, screenLookups.get(), "capture must not begin before its audit record is durable");
        assertThrows(RemoteException.class, () -> handler.executeInput(session, TOKEN,
                new RemoteInputCommand(RemoteInputCommand.CommandType.MOUSE_MOVE, 10, 10,
                        0, 0, 0, 1)));
        assertEquals(0, actions.get(), "input action must not run when its audit append fails");
        handler.close();
    }

    @Test
    void fileCreationRejectsDirectoryAsAuditTarget() throws Exception {
        Path directory = Files.createTempDirectory("assistance-audit-target");
        assertThrows(IOException.class, () -> new AuditLog(directory));
    }
}

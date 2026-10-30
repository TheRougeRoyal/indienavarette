package com.veridion.assistance;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Synchronized append-only assistance audit log. The no-argument form is test-only and in-memory. */
public class AuditLog implements AutoCloseable {
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    public record AuditEntry(String operatorId, String action, String details, long timestamp,
                             String sessionId) { }

    private final Path path;
    private final List<AuditEntry> testEntries;
    private FileChannel channel;
    private boolean closed;

    /** Opens or creates an owner-readable, owner-writable audit file. */
    public AuditLog(Path path) throws IOException {
        if (path == null) throw new IOException("Assistance audit log path is required");
        this.path = path.toAbsolutePath().normalize();
        this.testEntries = null;
        openSecureFile();
    }

    /** In-memory audit sink for package tests only. */
    AuditLog() {
        this.path = null;
        this.testEntries = new ArrayList<>();
    }

    private void openSecureFile() throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            if (Files.isSymbolicLink(path)) throw new IOException("Audit log must not be a symbolic link", exists);
            try {
                Files.setPosixFilePermissions(path, OWNER_ONLY);
            } catch (UnsupportedOperationException | IOException e) {
                throw new IOException("Cannot enforce owner-only permissions on assistance audit log", e);
            }
        } catch (UnsupportedOperationException e) {
            throw new IOException("Owner-only audit log permissions are not supported", e);
        }
        try {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!actual.equals(OWNER_ONLY))
                throw new IOException("Cannot enforce owner-only permissions on assistance audit log");
            channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException e) {
            throw new IOException("Owner-only audit log permissions are not supported", e);
        }
    }

    public synchronized void log(String operatorName, String action, String sessionId, String details)
            throws IOException {
        if (closed) throw new IOException("Assistance audit log is closed");
        long timestamp = System.currentTimeMillis();
        AuditEntry entry = new AuditEntry(operatorName, action, details, timestamp, sessionId);
        if (testEntries != null) {
            testEntries.add(entry);
            return;
        }
        if (channel == null || !channel.isOpen()) throw new IOException("Assistance audit log is closed");
        String line = escape(Instant.ofEpochMilli(timestamp).toString()) + '\t'
                + escape(operatorName) + '\t' + escape(action) + '\t'
                + escape(sessionId) + '\t' + escape(details) + '\n';
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(line);
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    }

    public synchronized List<AuditEntry> getEntries() {
        if (testEntries != null) return new ArrayList<>(testEntries);
        List<AuditEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] columns = line.split("\\t", -1);
                if (columns.length != 5) continue;
                long timestamp = Instant.parse(unescape(columns[0])).toEpochMilli();
                entries.add(new AuditEntry(unescape(columns[1]), unescape(columns[2]),
                        unescape(columns[4]), timestamp, unescape(columns[3])));
            }
        } catch (IOException | RuntimeException ignored) {
            return entries;
        }
        return entries;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!escaped && character == '\\') { escaped = true; continue; }
            if (escaped) {
                result.append(switch (character) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> character;
                });
                escaped = false;
            } else result.append(character);
        }
        if (escaped) result.append('\\');
        return result.toString();
    }

    @Override public synchronized void close() throws IOException {
        if (channel != null) channel.close();
        closed = true;
    }
}

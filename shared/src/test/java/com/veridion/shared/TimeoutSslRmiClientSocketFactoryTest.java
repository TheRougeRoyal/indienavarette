package com.veridion.shared;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TimeoutSslRmiClientSocketFactoryTest {
    private static final char[] PASSWORD = "testpass".toCharArray();
    private static Path directory;
    private static SSLContext serverContext;
    private static SSLContext trustedClientContext;
    private static SSLContext untrustedClientContext;

    @BeforeAll static void createTestCertificates() throws Exception {
        directory = Files.createTempDirectory("rmi-tls-test");
        Path identity = directory.resolve("server.p12");
        Path certificate = directory.resolve("server.crt");
        Path trust = directory.resolve("trust.p12");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", identity.toString(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-noprompt");
        keytool("-exportcert", "-alias", "server", "-keystore", identity.toString(), "-storepass", new String(PASSWORD),
                "-rfc", "-file", certificate.toString());
        keytool("-importcert", "-alias", "server", "-file", certificate.toString(), "-storetype", "PKCS12", "-keystore", trust.toString(),
                "-storepass", new String(PASSWORD), "-noprompt");

        KeyStore identityStore = load(identity);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(identityStore, PASSWORD);
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);
        trustedClientContext = clientContext(load(trust));
        untrustedClientContext = clientContext(KeyStore.getInstance("PKCS12"));
    }

    @AfterAll static void removeTestCertificates() throws Exception {
        if (directory == null) return;
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    @Test void acceptsTrustedCertificateForRequestedHostname() throws Exception {
        withTlsServer(port -> {
            var socketFactory = new TimeoutSslRmiClientSocketFactory(3000, 3000, trustedClientContext.getSocketFactory());
            try (var socket = socketFactory.createSocket("localhost", port)) {
                assertTrue(socket.isConnected());
            }
        });
    }

    @Test void rejectsTrustedCertificateWhenHostnameDoesNotMatch() throws Exception {
        withTlsServer(port -> {
            var socketFactory = new TimeoutSslRmiClientSocketFactory(3000, 3000, trustedClientContext.getSocketFactory());
            assertThrows(SSLHandshakeException.class, () -> socketFactory.createSocket("127.0.0.1", port));
        });
    }

    @Test void rejectsCertificateFromUntrustedIssuer() throws Exception {
        withTlsServer(port -> {
            var socketFactory = new TimeoutSslRmiClientSocketFactory(3000, 3000, untrustedClientContext.getSocketFactory());
            assertThrows(SSLHandshakeException.class, () -> socketFactory.createSocket("localhost", port));
        });
    }

    private static void withTlsServer(PortAction action) throws Exception {
        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory().createServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "rmi-tls-test-server");
                thread.setDaemon(true);
                return thread;
            });
            try {
                var accepted = executor.submit(() -> {
                    try (SSLSocket socket = (SSLSocket) server.accept()) { socket.startHandshake(); }
                    catch (Exception ignored) { /* failed client handshakes are asserted by the client side */ }
                });
                action.run(server.getLocalPort());
                accepted.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(1, TimeUnit.SECONDS);
            }
        }
    }

    private static KeyStore load(Path path) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(path)) { store.load(input, PASSWORD); }
        return store;
    }

    private static SSLContext clientContext(KeyStore trustStore) throws Exception {
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    private static void keytool(String... args) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "keytool");
        var command = new java.util.ArrayList<String>();
        command.add(executable.toString());
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output;
        try (InputStream input = process.getInputStream()) { output = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new IllegalStateException("keytool failed: " + output);
    }

    @FunctionalInterface private interface PortAction { void run(int port) throws Exception; }
}

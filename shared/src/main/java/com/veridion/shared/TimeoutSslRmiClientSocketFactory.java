package com.veridion.shared;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLParameters;

/** TLS RMI sockets with bounded connect and read waits, preventing a stuck retry worker. */
public final class TimeoutSslRmiClientSocketFactory implements RMIClientSocketFactory, Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final transient SSLSocketFactory socketFactory;

    public TimeoutSslRmiClientSocketFactory() {
        this(Integer.getInteger("activity.rmi.connect-timeout-ms", 5000), Integer.getInteger("activity.rmi.read-timeout-ms", 10000));
    }

    public TimeoutSslRmiClientSocketFactory(int connectTimeoutMillis, int readTimeoutMillis) {
        this(connectTimeoutMillis, readTimeoutMillis, null);
    }

    TimeoutSslRmiClientSocketFactory(int connectTimeoutMillis, int readTimeoutMillis, SSLSocketFactory socketFactory) {
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) throw new IllegalArgumentException("Socket timeouts must be positive");
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.socketFactory = socketFactory;
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        Socket transport = new Socket();
        try {
            transport.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            SSLSocketFactory factory = socketFactory == null ? (SSLSocketFactory) SSLSocketFactory.getDefault() : socketFactory;
            SSLSocket socket = (SSLSocket) factory.createSocket(transport, host, port, true);
            socket.setSoTimeout(readTimeoutMillis);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) { transport.close(); throw e; }
    }

    @Override public boolean equals(Object other) {
        return other instanceof TimeoutSslRmiClientSocketFactory f
                && connectTimeoutMillis == f.connectTimeoutMillis && readTimeoutMillis == f.readTimeoutMillis;
    }
    @Override public int hashCode() { return 31 * connectTimeoutMillis + readTimeoutMillis; }
}

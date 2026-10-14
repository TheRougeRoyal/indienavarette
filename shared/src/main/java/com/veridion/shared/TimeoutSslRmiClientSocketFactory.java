package com.veridion.shared;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** TLS RMI sockets with bounded connect and read waits, preventing a stuck retry worker. */
public final class TimeoutSslRmiClientSocketFactory implements RMIClientSocketFactory, Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public TimeoutSslRmiClientSocketFactory() {
        this(Integer.getInteger("activity.rmi.connect-timeout-ms", 5000), Integer.getInteger("activity.rmi.read-timeout-ms", 10000));
    }

    public TimeoutSslRmiClientSocketFactory(int connectTimeoutMillis, int readTimeoutMillis) {
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) throw new IllegalArgumentException("Socket timeouts must be positive");
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        Socket socket = SSLSocketFactory.getDefault().createSocket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            ((SSLSocket) socket).startHandshake();
            return socket;
        } catch (IOException e) { socket.close(); throw e; }
    }

    @Override public boolean equals(Object other) {
        return other instanceof TimeoutSslRmiClientSocketFactory f
                && connectTimeoutMillis == f.connectTimeoutMillis && readTimeoutMillis == f.readTimeoutMillis;
    }
    @Override public int hashCode() { return 31 * connectTimeoutMillis + readTimeoutMillis; }
}

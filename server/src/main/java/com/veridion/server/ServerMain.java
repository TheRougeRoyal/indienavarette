package com.veridion.server;

import com.veridion.shared.RmiConfig;
import javax.rmi.ssl.SslRMIServerSocketFactory;
import com.veridion.shared.TimeoutSslRmiClientSocketFactory;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.RemoteException;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.ObjectInputFilter;

public class ServerMain {
    public static void main(String[] args) {
        try {
            configureRmiEndpoint();
            installSerializationFilter();
            ActivityServerImpl server = new ActivityServerImpl();
            int port = RmiConfig.port();
            // ponytail: Registry and Remote Object share the same port for VPS/Firewall friendliness
            Registry registry = LocateRegistry.createRegistry(port, new TimeoutSslRmiClientSocketFactory(),
                    new SslRMIServerSocketFactory(null, null, false));
            registry.rebind(RmiConfig.BINDING, server);
            System.out.println("Activity Server is running on port " + port + "...");

            if (dashboardEnabled(args) && !GraphicsEnvironment.isHeadless()) {
                SwingUtilities.invokeLater(() -> new DashboardUI(server).setVisible(true));
            } else {
                System.out.println("Dashboard disabled; server is running headless.");
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void configureRmiEndpoint() {
        String advertisedHost = System.getProperty("activity.server.advertised-host");
        if (advertisedHost == null || advertisedHost.isBlank())
            advertisedHost = System.getProperty("java.rmi.server.hostname");
        if (advertisedHost == null || advertisedHost.isBlank())
            throw new IllegalArgumentException("Set -Dactivity.server.advertised-host to the DNS name or IP clients use");
        System.setProperty("java.rmi.server.hostname", advertisedHost);
    }

    private static boolean dashboardEnabled(String[] args) {
        if (Boolean.getBoolean("activity.dashboard.disabled")) return false;
        for (String arg : args) if ("--no-dashboard".equals(arg)) return false;
        return true;
    }

    private static void installSerializationFilter() {
        // Bound incoming RMI object graphs and accept only the DTO and JDK types
        // used by this service. The JVM-wide filter must be installed before export.
        ObjectInputFilter.Config.setSerialFilter(serializationFilter());
    }

    static ObjectInputFilter serializationFilter() {
        return ObjectInputFilter.Config.createFilter(
                "maxdepth=20;maxrefs=10000;maxbytes=1048576;maxarray=10000;" +
                "com.veridion.shared.ActivityEvent;java.lang.String;java.time.Ser;" +
                "java.rmi.*;sun.rmi.*;javax.rmi.*;!*");
    }
}

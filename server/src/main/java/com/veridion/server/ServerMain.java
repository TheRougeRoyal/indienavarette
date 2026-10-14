package com.veridion.server;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.RemoteException;
import javax.swing.SwingUtilities;

public class ServerMain {
    public static void main(String[] args) {
        try {
            String advertisedHost = System.getProperty("java.rmi.server.hostname");
            if (advertisedHost == null || advertisedHost.isBlank()) {
                System.setProperty("java.rmi.server.hostname", java.net.InetAddress.getLocalHost().getHostAddress());
            }
            ActivityServerImpl server = new ActivityServerImpl();
            int port = Integer.getInteger("activity.server.port", 1099);
            Registry registry = LocateRegistry.createRegistry(port);
            registry.rebind("ActivityServer", server);
            System.out.println("Activity Server is running on port " + port + "...");

            SwingUtilities.invokeLater(() -> {
                DashboardUI dashboard = new DashboardUI(server);
                dashboard.setVisible(true);
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

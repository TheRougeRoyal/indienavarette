package com.veridion.server;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.RemoteException;
import javax.swing.SwingUtilities;

public class ServerMain {
    public static void main(String[] args) {
        try {
            ActivityServerImpl server = new ActivityServerImpl();
            Registry registry = LocateRegistry.createRegistry(1099);
            registry.rebind("ActivityServer", server);
            System.out.println("Activity Server is running on port 1099...");

            SwingUtilities.invokeLater(() -> {
                DashboardUI dashboard = new DashboardUI(server);
                dashboard.setVisible(true);
            });
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}

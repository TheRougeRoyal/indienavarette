package com.veridion.server;

import com.veridion.shared.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.NavigableMap;

public class DashboardUI extends JFrame {
    private final ActivityServerImpl server;
    private final JTable clientTable;
    private final DefaultTableModel clientTableModel;
    private final JTextArea activityLogArea;
    private final JComboBox<String> clientSelector;
    private final JTextField searchField;
    private final JButton exportBtn;


    public DashboardUI(ActivityServerImpl server) {
        this.server = server;

        setTitle("Veridion Activity Dashboard");
        setSize(1000, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // --- Client List Panel (Left) ---
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Connected Clients"));

        String[] columns = {"Client ID", "Hostname", "Last Seen"};
        clientTableModel = new DefaultTableModel(columns, 0);
        clientTable = new JTable(clientTableModel);
        leftPanel.add(new JScrollPane(clientTable), BorderLayout.CENTER);

        JButton refreshBtn = new JButton("Refresh Clients");
        refreshBtn.addActionListener(e -> updateClientList());
        leftPanel.add(refreshBtn, BorderLayout.SOUTH);

        add(leftPanel, BorderLayout.WEST);

        // --- Activity Logs Panel (Center) ---
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setBorder(BorderFactory.createTitledBorder("Activity Logs"));

        JPanel topCenter = new JPanel(new FlowLayout(FlowLayout.LEFT));
        clientSelector = new JComboBox<>();
        clientSelector.addActionListener(e -> updateActivityLogs());

        searchField = new JTextField(15);
        searchField.addActionListener(e -> updateActivityLogs());

        exportBtn = new JButton("Export CSV");
        exportBtn.addActionListener(e -> exportToCsv());

        JLabel label = new JLabel("Client: ");
        topCenter.add(label);
        topCenter.add(clientSelector);
        topCenter.add(new JLabel(" Filter: "));
        topCenter.add(searchField);
        topCenter.add(exportBtn);


        activityLogArea = new JTextArea();
        activityLogArea.setEditable(false);
        activityLogArea.setFont(new Font("Monospaced", Font.PLAIN, 12));

        centerPanel.add(topCenter, BorderLayout.NORTH);
        centerPanel.add(new JScrollPane(activityLogArea), BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);

        updateClientList();
        new javax.swing.Timer(5000, e -> { updateClientList(); updateActivityLogs(); }).start();
    }

    private void updateClientList() {
        String previouslySelected = (String) clientSelector.getSelectedItem();
        clientTableModel.setRowCount(0);
        Map<String, ActivityServerImpl.ClientSession> clients = server.getClients();

        clientSelector.removeAllItems();
        clientSelector.addItem("None");

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        clients.forEach((id, session) -> {
            clientTableModel.addRow(new Object[]{
                id,
                session.getHostname(),
                session.getLastSeen().format(formatter) + (server.isConnected(session) ? " (online)" : " (expired)")
            });
            clientSelector.addItem(id);
        });
        if (previouslySelected != null) clientSelector.setSelectedItem(previouslySelected);
    }

    private void updateActivityLogs() {
        String selectedId = (String) clientSelector.getSelectedItem();
        if (selectedId == null || selectedId.equals("None")) {
            activityLogArea.setText("Please select a client to view logs.");
            return;
        }

        NavigableMap<LocalDateTime, ActivityEvent> logs = server.getLogsForClient(selectedId);
        if (logs == null || logs.isEmpty()) {
            activityLogArea.setText("No logs found for this client.");
            return;
        }

        String filter = searchField.getText().toLowerCase();
        StringBuilder sb = new StringBuilder();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        logs.descendingMap().forEach((time, event) -> {
            String app = event.appName();
            String title = event.windowTitle();
            if (filter.isBlank() || app.toLowerCase().contains(filter) || title.toLowerCase().contains(filter)) {
                sb.append(String.format("[%s] %s | %s\n",
                    time.format(formatter),
                    app,
                    title));
            }
        });
        activityLogArea.setText(sb.toString());
    }

    private void exportToCsv() {
        String selectedId = (String) clientSelector.getSelectedItem();
        if (selectedId == null || selectedId.equals("None")) {
            JOptionPane.showMessageDialog(this, "Please select a client first.");
            return;
        }

        NavigableMap<LocalDateTime, ActivityEvent> logs = server.getLogsForClient(selectedId);
        if (logs == null || logs.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No logs available to export.");
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new java.io.File(selectedId + "_activity.csv"));
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File file = fileChooser.getSelectedFile();
            try (java.io.PrintWriter writer = new java.io.PrintWriter(file)) {
                writer.println("Timestamp,Application,WindowTitle");
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                logs.forEach((time, event) -> {
                    writer.printf("\"%s\",\"%s\",\"%s\"\n",
                        time.format(formatter),
                        event.appName().replace("\"", "\"\""),
                        event.windowTitle().replace("\"", "\"\""));
                });
                JOptionPane.showMessageDialog(this, "Export successful!");
            } catch (java.io.IOException e) {
                JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

}

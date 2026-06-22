package burpmcp.ui;

import burp.api.montoya.MontoyaApi;
import burpmcp.models.ServerLogListModel;
import burpmcp.models.ServerLogTableModel;
import burpmcp.BurpMCPPersistence;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class ServerLogsPanel extends JPanel {
    private final ServerLogListModel serverLogListModel;
    private final JTable serverLogTable;
    private final TableRowSorter<ServerLogTableModel> tableSorter;
    private final BurpMCPPersistence persistence;
    private static final String TABLE_KEY = "serverLogs";

    public ServerLogsPanel(MontoyaApi api, ServerLogListModel serverLogListModel) {
        super(new BorderLayout());
        this.serverLogListModel = serverLogListModel;
        this.persistence = new BurpMCPPersistence(api);
        
        // Create the server logs table with updated column names
        String[] columnNames = {"ID", "Time", "Direction", "Client", "Tool", "Size"};
        ServerLogTableModel tableModel = new ServerLogTableModel(serverLogListModel, columnNames);
        serverLogTable = new JTable(tableModel);
        serverLogTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        
        // Create and set the table sorter
        tableSorter = new TableRowSorter<>(tableModel);
        serverLogTable.setRowSorter(tableSorter);
        
        // Connect the table model to the list model
        serverLogListModel.setTableModel(tableModel);
        
        // Set column widths
        serverLogTable.getColumnModel().getColumn(0).setPreferredWidth(50);  // ID column
        serverLogTable.getColumnModel().getColumn(1).setPreferredWidth(200); // Time
        serverLogTable.getColumnModel().getColumn(2).setPreferredWidth(100); // Direction
        serverLogTable.getColumnModel().getColumn(3).setPreferredWidth(150); // Client
        serverLogTable.getColumnModel().getColumn(4).setPreferredWidth(200); // Tool
        serverLogTable.getColumnModel().getColumn(5).setPreferredWidth(100); // Size
        
        // Center all columns
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 0; i < serverLogTable.getColumnCount(); i++) {
            serverLogTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }
        
        // Add listener to save sorting state when it changes
        tableSorter.addRowSorterListener(e -> {
            persistence.saveTableSortingState(TABLE_KEY, tableSorter);
        });
        
        // Create a scroll pane for the table
        JScrollPane tableScrollPane = new JScrollPane(serverLogTable);
        
        // Create the detail panel for message data
        ServerLogDetailPanel detailPanel = new ServerLogDetailPanel();
        
        // Add selection listener to show message details
        serverLogTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int selectedRow = serverLogTable.getSelectedRow();
                if (selectedRow >= 0) {
                    // Convert view index to model index
                    int modelRow = serverLogTable.convertRowIndexToModel(selectedRow);
                    String messageData = serverLogListModel.getEntry(modelRow).getMessageData();
                    detailPanel.setMessageData(messageData);
                }
            }
        });
        
        // Right-click popup
        serverLogTable.addMouseListener(TablePopups.selectRowOnRightClick(serverLogTable));
        serverLogTable.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent ev)  { maybeShow(ev); }
            @Override public void mouseReleased(MouseEvent ev) { maybeShow(ev); }
            private void maybeShow(MouseEvent ev) {
                if (!ev.isPopupTrigger()) return;
                buildPopup().show(ev.getComponent(), ev.getX(), ev.getY());
            }
        });
        serverLogTable.setComponentPopupMenu(new JPopupMenu() {
            @Override public void show(Component invoker, int x, int y) {
                removeAll();
                JPopupMenu fresh = buildPopup();
                for (Component c : fresh.getComponents()) add(c);
                super.show(invoker, x, y);
            }
        });
        serverLogTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "burpmcp.delete");
        serverLogTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "burpmcp.delete");
        serverLogTable.getActionMap().put("burpmcp.delete", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                deleteSelectedRows();
            }
        });

        // Create a split pane to divide the table and message data
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScrollPane, detailPanel);
        splitPane.setResizeWeight(0.5); // 50-50 split
        
        // Create a panel for the clear button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton deleteSelectedButton = new JButton("Delete Selected");
        deleteSelectedButton.setToolTipText("Remove highlighted rows. Shortcut: Delete / Backspace.");
        deleteSelectedButton.addActionListener(e -> deleteSelectedRows());
        buttonPanel.add(deleteSelectedButton);

        JButton clearButton = new JButton("Clear Server Logs");
        clearButton.addActionListener(e -> {
            serverLogListModel.clear();
            serverLogTable.updateUI();
        });
        buttonPanel.add(clearButton);
        
        // Add the button panel and split pane
        add(buttonPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
    }
    
    /**
     * Restores the table sorting state from persistence
     */
    public void restoreTableSortingState() {
        persistence.restoreTableSortingState(TABLE_KEY, tableSorter);
    }
    
    /**
     * Gets the table sorter for external access
     */
    public TableRowSorter<ServerLogTableModel> getTableSorter() {
        return tableSorter;
    }

    public JTable getServerLogTable() {
        return serverLogTable;
    }

    private void deleteSelectedRows() {
        int[] desc = TablePopups.selectedModelRowsDescending(serverLogTable);
        if (desc.length == 0) return;
        serverLogListModel.removeRows(desc);
        serverLogTable.updateUI();
    }

    private JPopupMenu buildPopup() {
        JPopupMenu menu = new JPopupMenu();
        int[] viewRows = serverLogTable.getSelectedRows();

        JMenuItem deleteItem = new JMenuItem("Delete selected (" + viewRows.length + ")");
        deleteItem.setEnabled(viewRows.length > 0);
        deleteItem.addActionListener(e -> deleteSelectedRows());
        menu.add(deleteItem);

        JMenuItem copyItem = new JMenuItem("Copy message data");
        copyItem.setEnabled(viewRows.length > 0);
        copyItem.addActionListener(e -> {
            StringBuilder sb = new StringBuilder();
            for (int v : viewRows) {
                int m = serverLogTable.convertRowIndexToModel(v);
                if (m >= 0 && m < serverLogListModel.getRowCount()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(serverLogListModel.getEntry(m).getMessageData());
                }
            }
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(sb.toString()), null);
            } catch (Exception ignore) {
            }
        });
        menu.add(copyItem);
        return menu;
    }
}

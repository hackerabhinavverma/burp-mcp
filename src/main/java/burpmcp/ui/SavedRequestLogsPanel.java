package burpmcp.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burpmcp.BurpMCP;
import burpmcp.models.SavedRequestListModel;
import burpmcp.models.SavedRequestTableModel;
import burpmcp.BurpMCPPersistence;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

public class SavedRequestLogsPanel extends JPanel {
    private final JTable requestTable;
    private final SavedRequestListModel savedRequestListModel;
    private final SavedRequestDetailPanel detailPanel;
    private final TableRowSorter<SavedRequestTableModel> tableSorter;
    private final BurpMCPPersistence persistence;
    private final BurpMCP burpMCP;
    private static final String TABLE_KEY = "savedRequests";

    public SavedRequestLogsPanel(MontoyaApi api, SavedRequestListModel savedRequestListModel, BurpMCP burpMCP) {
        super(new BorderLayout());
        this.savedRequestListModel = savedRequestListModel;
        this.persistence = new BurpMCPPersistence(api);
        this.burpMCP = burpMCP;

        // Create the table with expanded columns including Port, Secure, Status code and Response Length
        String[] columnNames = {"ID", "Time", "Host", "Port", "Secure", "Method", "Path", "Query", "Status", "Resp Len", "Notes"};
        SavedRequestTableModel tableModel = new SavedRequestTableModel(savedRequestListModel, columnNames);
        requestTable = new JTable(tableModel);
        requestTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Create and set the table sorter
        tableSorter = new TableRowSorter<>(tableModel);
        requestTable.setRowSorter(tableSorter);

        // Connect the table model to the list model
        savedRequestListModel.setTableModel(tableModel);

        // Set column widths
        requestTable.getColumnModel().getColumn(0).setPreferredWidth(50);
        requestTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        requestTable.getColumnModel().getColumn(2).setPreferredWidth(180);
        requestTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        requestTable.getColumnModel().getColumn(4).setPreferredWidth(70);
        requestTable.getColumnModel().getColumn(5).setPreferredWidth(80);
        requestTable.getColumnModel().getColumn(6).setPreferredWidth(220);
        requestTable.getColumnModel().getColumn(7).setPreferredWidth(220);
        requestTable.getColumnModel().getColumn(8).setPreferredWidth(80);
        requestTable.getColumnModel().getColumn(9).setPreferredWidth(100);
        requestTable.getColumnModel().getColumn(10).setPreferredWidth(250);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        for (int i = 0; i < requestTable.getColumnCount(); i++) {
            requestTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }

        tableSorter.addRowSorterListener(e -> {
            persistence.saveTableSortingState(TABLE_KEY, tableSorter);
        });

        JScrollPane tableScrollPane = new JScrollPane(requestTable);

        detailPanel = new SavedRequestDetailPanel(api);

        // Selection listener — only populate detail when a single row is selected.
        requestTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int[] selected = requestTable.getSelectedRows();
            if (selected.length == 1) {
                int modelRow = requestTable.convertRowIndexToModel(selected[0]);
                if (modelRow >= 0 && modelRow < savedRequestListModel.getRowCount()) {
                    HttpRequestResponse selectedRequest = savedRequestListModel.getRequestAt(modelRow);
                    detailPanel.setRequest(selectedRequest, modelRow, savedRequestListModel);
                }
            }
        });

        // Right-click popup — use both addMouseListener (defensive) AND setComponentPopupMenu
        requestTable.addMouseListener(TablePopups.selectRowOnRightClick(requestTable));
        requestTable.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                buildPopup().show(e.getComponent(), e.getX(), e.getY());
            }
        });
        // Fallback: Swing's standard popup trigger handler — covers cases where
        // isPopupTrigger() is not raised by the look-and-feel.
        requestTable.setComponentPopupMenu(new JPopupMenu() {
            @Override public void show(java.awt.Component invoker, int x, int y) {
                removeAll();
                JPopupMenu fresh = buildPopup();
                for (java.awt.Component c : fresh.getComponents()) add(c);
                super.show(invoker, x, y);
            }
        });

        // DELETE key removes selected rows
        requestTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "burpmcp.delete");
        requestTable.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "burpmcp.delete");
        requestTable.getActionMap().put("burpmcp.delete", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                deleteSelectedRows();
            }
        });

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScrollPane, detailPanel);
        splitPane.setResizeWeight(0.4);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton deleteSelectedButton = new JButton("Delete Selected");
        deleteSelectedButton.setToolTipText("Remove highlighted rows from the saved request list. Shortcut: Delete / Backspace.");
        deleteSelectedButton.addActionListener(e -> deleteSelectedRows());
        buttonPanel.add(deleteSelectedButton);

        JButton clearButton = new JButton("Clear Saved Requests");
        clearButton.addActionListener(e -> {
            savedRequestListModel.clear();
            requestTable.updateUI();
        });
        buttonPanel.add(clearButton);

        add(buttonPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
    }

    private void deleteSelectedRows() {
        int[] desc = TablePopups.selectedModelRowsDescending(requestTable);
        if (desc.length == 0) return;
        savedRequestListModel.removeRows(desc);
        requestTable.updateUI();
    }

    private JPopupMenu buildPopup() {
        JPopupMenu menu = new JPopupMenu();
        List<HttpRequestResponse> selected = collectSelectedRequests();

        JMenuItem deleteItem = new JMenuItem("Delete selected (" + selected.size() + ")");
        deleteItem.setEnabled(!selected.isEmpty());
        deleteItem.addActionListener(e -> deleteSelectedRows());
        menu.add(deleteItem);

        if (!selected.isEmpty() && burpMCP != null) {
            menu.addSeparator();
            for (JMenuItem mi : burpMCP.buildBurpMcpActionItems(selected)) {
                if (mi == null) menu.addSeparator();
                else menu.add(mi);
            }
        }
        return menu;
    }

    private List<HttpRequestResponse> collectSelectedRequests() {
        List<HttpRequestResponse> out = new ArrayList<>();
        int[] viewRows = requestTable.getSelectedRows();
        for (int v : viewRows) {
            int m = requestTable.convertRowIndexToModel(v);
            if (m >= 0 && m < savedRequestListModel.getRowCount()) {
                out.add(savedRequestListModel.getRequestAt(m));
            }
        }
        return out;
    }

    public void restoreTableSortingState() {
        persistence.restoreTableSortingState(TABLE_KEY, tableSorter);
    }

    public TableRowSorter<SavedRequestTableModel> getTableSorter() {
        return tableSorter;
    }

    public JTable getRequestTable() {
        return requestTable;
    }
}

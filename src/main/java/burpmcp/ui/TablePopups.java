package burpmcp.ui;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;

/** Small helpers shared by the BurpMCP table panels for right-click popups. */
public final class TablePopups {
    private TablePopups() {}

    /**
     * Returns a MouseAdapter that, on a popup trigger (right-click on macOS or
     * the appropriate platform gesture), ensures the row under the cursor is
     * part of the table's selection — matching Burp's native behaviour.
     */
    public static MouseAdapter selectRowOnRightClick(JTable table) {
        return new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeSelect(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeSelect(e); }

            private void maybeSelect(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                if (!table.isRowSelected(viewRow)) {
                    table.setRowSelectionInterval(viewRow, viewRow);
                }
            }
        };
    }

    /**
     * Selected rows in MODEL coordinates, sorted descending so callers can
     * safely remove them from a backing list without index shift bugs.
     */
    public static int[] selectedModelRowsDescending(JTable table) {
        int[] viewRows = table.getSelectedRows();
        int[] modelRows = new int[viewRows.length];
        for (int i = 0; i < viewRows.length; i++) {
            modelRows[i] = table.convertRowIndexToModel(viewRows[i]);
        }
        Arrays.sort(modelRows);
        // reverse to descending
        for (int i = 0, j = modelRows.length - 1; i < j; i++, j--) {
            int tmp = modelRows[i];
            modelRows[i] = modelRows[j];
            modelRows[j] = tmp;
        }
        return modelRows;
    }
}

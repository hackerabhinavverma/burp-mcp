package burpmcp.ui;

import javax.swing.*;
import java.awt.*;

/** Tiny coloured circle used as a traffic-light health indicator. */
public class StatusDot extends JComponent {
    public enum Status { GREEN, AMBER, RED, GRAY }

    private Status status = Status.GRAY;
    private final int diameter;

    public StatusDot() {
        this(12);
    }

    public StatusDot(int diameter) {
        this.diameter = diameter;
        setPreferredSize(new Dimension(diameter + 4, diameter + 4));
    }

    public void setStatus(Status s) {
        if (s != this.status) {
            this.status = s;
            repaint();
        }
    }

    public Status getStatus() {
        return status;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill;
            switch (status) {
                case GREEN: fill = new Color(0x2ECC71); break;
                case AMBER: fill = new Color(0xF39C12); break;
                case RED:   fill = new Color(0xE74C3C); break;
                default:    fill = new Color(0x95A5A6); break;
            }
            int x = (getWidth() - diameter) / 2;
            int y = (getHeight() - diameter) / 2;
            g2.setColor(fill);
            g2.fillOval(x, y, diameter, diameter);
            g2.setColor(fill.darker());
            g2.drawOval(x, y, diameter, diameter);
        } finally {
            g2.dispose();
        }
    }
}

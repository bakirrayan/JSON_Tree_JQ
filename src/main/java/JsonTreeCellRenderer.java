import javax.swing.*;
import javax.swing.tree.TreeCellRenderer;
import java.awt.*;

public class JsonTreeCellRenderer implements TreeCellRenderer {

    private static final Font MONO      = new Font("Roboto", Font.PLAIN, 13);
    private static final Font MONO_BOLD = new Font("Roboto", Font.BOLD,  13);

    private final CellPanel panel = new CellPanel();

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
            boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        panel.configure(value, selected);
        return panel;
    }

    /** Returns true if the current LAF background is dark. Checked at paint time so it adapts live. */
    static boolean isDark() {
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) return false;
        int lum = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000;
        return lum < 128;
    }

    private static class CellPanel extends JPanel {
        private final JLabel keyLabel   = new JLabel();
        private final JLabel colonLabel = new JLabel(": ");
        private final JLabel valLabel   = new JLabel();

        CellPanel() {
            setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 8));

            keyLabel.setFont(MONO_BOLD);
            colonLabel.setFont(MONO);
            valLabel.setFont(MONO);

            add(keyLabel);
            add(colonLabel);
            add(valLabel);
        }

        void configure(Object value, boolean selected) {
            boolean dark = isDark();

            // ── Backgrounds ─────────────────────────────────────────────────
            Color treeBg = UIManager.getColor("Tree.background");
            Color selBg  = UIManager.getColor("Tree.selectionBackground");
            if (treeBg == null) treeBg = dark ? new Color(0x2B, 0x2B, 0x2B) : Color.WHITE;
            if (selBg  == null) selBg  = new Color(0x26, 0x59, 0xA8);
            setBackground(selected ? selBg : treeBg);

            // Compute text-on-selection color from actual selection bg brightness
            int selLum = (selBg.getRed() * 299 + selBg.getGreen() * 587 + selBg.getBlue() * 114) / 1000;
            Color onSel     = selLum < 128 ? Color.WHITE        : new Color(0x1A, 0x1A, 0x1A);
            Color onSelDim  = selLum < 128 ? new Color(0xCC, 0xCC, 0xCC) : new Color(0x44, 0x44, 0x44);

            // ── Syntax colours (light / dark variants) ───────────────────────
            Color keyColor     = dark ? new Color(0x88, 0xC0, 0xF5) : new Color(0x4A, 0x9E, 0xF0);
            Color stringColor  = dark ? new Color(0x7B, 0xC9, 0x8D) : new Color(0x3D, 0xB8, 0x6C);
            Color numberColor  = dark ? new Color(0xE8, 0xB4, 0x6A) : new Color(0xE8, 0x94, 0x30);
            Color boolColor    = dark ? new Color(0xE0, 0x70, 0x70) : new Color(0xD9, 0x53, 0x4F);
            Color nullColor    = new Color(0x88, 0x88, 0x88);
            Color bracketColor = dark ? new Color(0x77, 0x77, 0x77) : new Color(0x99, 0x99, 0x99);
            Color colonColor   = dark ? new Color(0x55, 0x55, 0x55) : new Color(0xAA, 0xAA, 0xAA);

            // ── Non-JsonTreeNode fallback ────────────────────────────────────
            if (!(value instanceof JsonTreeNode node)) {
                keyLabel.setVisible(false);
                colonLabel.setVisible(false);
                valLabel.setText(value != null ? value.toString() : "");
                valLabel.setForeground(selected ? onSel : bracketColor);
                return;
            }

            JsonTreeNode.Kind kind = node.getKind();
            String key = node.getKey();
            String val = node.getValue();

            // Key
            boolean hasKey = key != null && !key.isEmpty();
            keyLabel.setVisible(hasKey);
            colonLabel.setVisible(hasKey);
            if (hasKey) {
                keyLabel.setText(key);
                keyLabel.setForeground(selected ? onSel : keyColor);
            }
            colonLabel.setForeground(selected ? onSelDim : colonColor);

            // Value
            Color valueColor = switch (kind) {
                case STRING  -> stringColor;
                case NUMBER  -> numberColor;
                case BOOLEAN -> boolColor;
                case NULL    -> nullColor;
                default      -> bracketColor;
            };
            String display = switch (kind) {
                case STRING -> "\"" + val + "\"";
                case OBJECT -> "{" + node.getChildCount() + "}";
                case ARRAY  -> "[" + node.getChildCount() + "]";
                default     -> val;
            };
            valLabel.setText(display);
            valLabel.setForeground(selected ? onSelDim : valueColor);
        }
    }
}

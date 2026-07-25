import javax.swing.UIManager;
import java.awt.Font;

/**
 * Fonts derived from the current look and feel rather than hardcoded, so the extension follows
 * Burp's theme and font-size setting. Resolved on every call (like
 * {@link JsonTreeCellRenderer#isDark()}) so a live theme change is picked up.
 */
public final class JsonFonts {

    private static final int FALLBACK_SIZE = 12;

    private JsonFonts() {}

    /** The LAF's tree font, or a sane default when the LAF doesn't define one. */
    public static Font ui() {
        Font f = UIManager.getFont("Tree.font");
        if (f == null) f = UIManager.getFont("Label.font");
        return f != null ? f : new Font(Font.DIALOG, Font.PLAIN, FALLBACK_SIZE);
    }

    public static Font bold() {
        return ui().deriveFont(Font.BOLD);
    }

    /** Smaller variant for the status bar, line-number ruler and error label. */
    public static Font small() {
        Font f = ui();
        return f.deriveFont(Font.PLAIN, Math.max(9f, f.getSize2D() - 1f));
    }

    /** Monospaced at the LAF's size — used for JSON values and the jq query field. */
    public static Font mono() {
        return new Font(Font.MONOSPACED, Font.PLAIN, ui().getSize());
    }
}

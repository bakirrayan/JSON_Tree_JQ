import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;

public class JsonTreePanel extends JPanel {

    private final JTree tree;
    private final JqSearchBar searchBar;
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel pythonLabel = new JLabel(" ");
    private final JqRunner jqRunner;

    // The parsed response body. Set ONLY when a new HTTP response arrives — never overwritten by
    // queries — and reused by both jq and autocomplete so the body is never re-parsed.
    private JsonNode originalRoot;

    // Maps every tree node → its absolute pre-order index across the full (unexpanded) tree.
    // Used by LineNumberView so collapsed nodes still show their real position.
    private final Map<Object, Integer> nodeAbsIndex = new IdentityHashMap<>();

    public JsonTreePanel(JqRunner jqRunner) {
        this.jqRunner = jqRunner;
        setLayout(new BorderLayout());

        tree = new JTree((DefaultTreeModel) null);
        tree.setCellRenderer(new JsonTreeCellRenderer());
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0); // auto-size each row from the renderer's preferred height
        tree.setFont(JsonFonts.ui());

        // Status labels — show the jq and Python paths of the selected node
        tree.addTreeSelectionListener(e -> {
            TreePath path = tree.getSelectionPath();
            if (path == null) {
                statusLabel.setText(" ");
                pythonLabel.setText(" ");
                return;
            }
            statusLabel.setText(buildJqPath(path));
            pythonLabel.setText(buildPythonPath(path));
        });

        // Ctrl+C: copy "key: value" of the selected node
        tree.getActionMap().put("copy", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                copySelected(CopyMode.KEY_VALUE);
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke("ctrl C"), "copy");

        // Right-click context menu — use isPopupTrigger() for cross-platform correctness
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { handleMouse(e); }
            @Override public void mouseReleased(MouseEvent e) { handleMouse(e); }

            private void handleMouse(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = tree.getRowForLocation(e.getX(), e.getY());
                    if (row >= 0) {
                        tree.setSelectionRow(row);
                        showContextMenu(e);
                    }
                }
            }
        });

        searchBar = new JqSearchBar(e -> runQuery());
        searchBar.setSuggestionProvider(this::getSuggestions);

        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setRowHeaderView(new LineNumberView());

        JButton expandAll   = new JButton("Expand All");
        JButton collapseAll = new JButton("Collapse All");
        for (JButton btn : new JButton[]{expandAll, collapseAll}) {
            btn.setFont(JsonFonts.small());
            btn.setFocusPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        expandAll.addActionListener(e -> expandAll());
        collapseAll.addActionListener(e -> collapseAll());

        statusLabel.setFont(JsonFonts.small());
        pythonLabel.setFont(JsonFonts.small());

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bottomBar.add(expandAll);
        bottomBar.add(collapseAll);
        bottomBar.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(1, 16));
        }});
        bottomBar.add(statusLabel);
        bottomBar.add(new JSeparator(SwingConstants.VERTICAL) {{
            setPreferredSize(new Dimension(1, 16));
        }});
        bottomBar.add(pythonLabel);

        add(searchBar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottomBar, BorderLayout.SOUTH);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called when a new HTTP response arrives. This is the ONLY place originalRoot is written. */
    public void setModel(DefaultTreeModel model, JsonNode originalRoot) {
        this.originalRoot = originalRoot;
        applyModelToTree(model);
    }

    /** Clears the tree and reports why (e.g. the body isn't JSON). */
    public void showEmpty(String reason) {
        this.originalRoot = null;
        SwingUtilities.invokeLater(() -> {
            tree.setModel(new DefaultTreeModel(null));
            nodeAbsIndex.clear();
            statusLabel.setText(" ");
            pythonLabel.setText(" ");
            searchBar.showError(reason);
        });
    }

    /** Releases UI resources — called when the extension unloads. */
    public void dispose() {
        searchBar.dispose();
    }

    /** Rebuilds the tree from an already-parsed node. Does NOT touch originalRoot. */
    public void rebuildTree(JsonNode node) {
        applyModelToTree(JsonTreeModel.build(node));
    }

    // ── Tree display ──────────────────────────────────────────────────────────

    private void applyModelToTree(DefaultTreeModel model) {
        SwingUtilities.invokeLater(() -> {
            tree.setModel(model);
            rebuildNodeIndex();          // must happen after setModel
            expandTopLevel(model);
            searchBar.clearError();
        });
    }

    /**
     * Expands only the hidden root's direct children. Expanding every row and collapsing back
     * down to depth 1 would walk the whole document on the EDT — seconds of freeze on a big body.
     */
    private void expandTopLevel(DefaultTreeModel model) {
        Object root = model.getRoot();
        if (root == null) return;
        tree.expandPath(new TreePath(root));
        int childCount = model.getChildCount(root);
        for (int i = 0; i < childCount; i++) {
            tree.expandPath(new TreePath(new Object[]{root, model.getChild(root, i)}));
        }
    }

    // ── Absolute node index (for line numbers) ────────────────────────────────

    /** Pre-order traversal of the full tree model, assigning 1-based indices to every node. */
    private void rebuildNodeIndex() {
        nodeAbsIndex.clear();
        TreeModel model = tree.getModel();
        if (model == null) return;
        Object root = model.getRoot();
        if (root == null) return;
        // Root is hidden — start numbering from its children so line 1 = first visible node.
        int[] counter = {0};
        int childCount = model.getChildCount(root);
        for (int i = 0; i < childCount; i++) {
            traverseIndex(model, model.getChild(root, i), counter);
        }
    }

    private void traverseIndex(TreeModel model, Object node, int[] counter) {
        nodeAbsIndex.put(node, ++counter[0]);
        int childCount = model.getChildCount(node);
        for (int i = 0; i < childCount; i++) {
            traverseIndex(model, model.getChild(node, i), counter);
        }
    }

    // ── jq query ──────────────────────────────────────────────────────────────

    /** Max entries in the autocomplete popup — an object with thousands of keys is unusable. */
    private static final int MAX_SUGGESTIONS = 50;

    /**
     * Runs on the EDT for every keystroke, so it walks the cached {@link #originalRoot} directly
     * instead of invoking jq — which would re-parse and re-compile the whole document each time.
     */
    private List<String> getSuggestions(String partialQuery) {
        if (originalRoot == null) return List.of();

        int lastDot = partialQuery.lastIndexOf('.');
        if (lastDot < 0) return List.of();

        String pathPrefix = partialQuery.substring(0, lastDot);
        String filter     = partialQuery.substring(lastDot + 1);

        if (filter.contains("[") || filter.contains("|") || filter.contains(" ")) return List.of();

        JsonNode result = resolvePath(pathPrefix);
        if (result == null) return List.of();

        // For arrays, suggest the keys of the first element — that's what .foo[] usually yields
        if (result.isArray() && result.size() > 0) result = result.get(0);
        if (result == null || !result.isObject()) return List.of();

        List<String> keys = new ArrayList<>();
        var names = result.fieldNames();
        while (names.hasNext() && keys.size() < MAX_SUGGESTIONS) {
            String k = names.next();
            if (filter.isEmpty() || k.startsWith(filter)) keys.add(k);
        }
        return keys;
    }

    /**
     * Resolves a simple dotted prefix such as {@code .user.address} against the cached document.
     * Returns null for anything more complex than plain field access — no suggestions is better
     * than blocking the EDT on jq.
     */
    private JsonNode resolvePath(String pathPrefix) {
        JsonNode node = originalRoot;
        if (pathPrefix.isEmpty() || pathPrefix.equals(".")) return node;
        for (String segment : pathPrefix.split("\\.", -1)) {
            if (segment.isEmpty()) continue;
            if (node == null || !node.isObject()) return null;
            node = node.get(segment);
        }
        return node;
    }

    private void runQuery() {
        String query = searchBar.getQuery();
        searchBar.clearError();

        if (originalRoot == null) return;
        if (jqRunner == null) { searchBar.showError("jq engine failed to initialize"); return; }

        if (query.isEmpty() || query.equals(".")) {
            rebuildTree(originalRoot);
            return;
        }

        JsonNode input = originalRoot;
        SwingWorker<DefaultTreeModel, Void> worker = new SwingWorker<>() {
            // Both the jq run and the tree build stay off the EDT
            @Override protected DefaultTreeModel doInBackground() throws Exception {
                return JsonTreeModel.build(jqRunner.runToNode(input, query));
            }
            @Override protected void done() {
                try {
                    applyModelToTree(get());
                } catch (Exception ex) {
                    searchBar.showError(ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    // ── Tree controls ─────────────────────────────────────────────────────────

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
    }

    private void collapseAll() {
        for (int i = tree.getRowCount() - 1; i >= 0; i--) tree.collapseRow(i);
    }

    // ── Copy helpers ──────────────────────────────────────────────────────────

    private enum CopyMode { KEY, VALUE, KEY_VALUE, JQ_PATH, PYTHON_PATH }

    private void copySelected(CopyMode mode) {
        TreePath path = tree.getSelectionPath();
        if (path == null) return;
        String text;
        if (mode == CopyMode.JQ_PATH) {
            text = buildJqPath(path);
        } else if (mode == CopyMode.PYTHON_PATH) {
            text = buildPythonPath(path);
        } else {
            Object last = path.getLastPathComponent();
            if (!(last instanceof JsonTreeNode node)) return;
            text = switch (mode) {
                case KEY       -> node.getKey() != null ? node.getKey() : "";
                case VALUE     -> node.getValue();
                case KEY_VALUE -> (node.getKey() != null ? node.getKey() + ": " : "") + node.getValue();
                default        -> "";
            };
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null);
    }

    private void showContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem copyValue = new JMenuItem("Copy value");
        copyValue.addActionListener(a -> copySelected(CopyMode.VALUE));
        menu.add(copyValue);

        JMenuItem copyKey = new JMenuItem("Copy key");
        copyKey.addActionListener(a -> copySelected(CopyMode.KEY));
        menu.add(copyKey);

        JMenuItem copyBoth = new JMenuItem("Copy key: value");
        copyBoth.addActionListener(a -> copySelected(CopyMode.KEY_VALUE));
        menu.add(copyBoth);

        menu.addSeparator();

        JMenuItem copyJqPath = new JMenuItem("Copy jq path");
        copyJqPath.addActionListener(a -> copySelected(CopyMode.JQ_PATH));
        menu.add(copyJqPath);

        JMenuItem copyPythonPath = new JMenuItem("Copy Python path");
        copyPythonPath.addActionListener(a -> copySelected(CopyMode.PYTHON_PATH));
        menu.add(copyPythonPath);

        menu.show(tree, e.getX(), e.getY());
    }

    // ── Line number ruler ─────────────────────────────────────────────────────

    private class LineNumberView extends JComponent {

        LineNumberView() {
            setFont(JsonFonts.small());
            setOpaque(true);

            TreeExpansionListener rel = new TreeExpansionListener() {
                public void treeExpanded(TreeExpansionEvent e)  { revalidate(); repaint(); }
                public void treeCollapsed(TreeExpansionEvent e) { revalidate(); repaint(); }
            };
            tree.addTreeExpansionListener(rel);
            tree.addPropertyChangeListener("model", e -> { revalidate(); repaint(); });
        }

        /** Dynamic width — grows as the total node count gains digits. */
        private int rulerWidth() {
            int maxNum = nodeAbsIndex.isEmpty() ? 9 : nodeAbsIndex.size();
            FontMetrics fm = getFontMetrics(JsonFonts.small());
            return fm.stringWidth("9".repeat(String.valueOf(maxNum).length())) + 14;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(rulerWidth(), tree.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            // Theme-aware colours, painted directly — mutating component state (setBackground /
            // setForeground) inside paint can re-trigger repaints.
            boolean dark = JsonTreeCellRenderer.isDark();
            Color bg  = dark ? new Color(0x2D, 0x2D, 0x2D) : new Color(0xF5, 0xF5, 0xF5);
            Color fg  = dark ? new Color(0x66, 0x66, 0x66) : new Color(0x99, 0x99, 0x99);
            Color div = dark ? new Color(0x44, 0x44, 0x44) : new Color(0xCC, 0xCC, 0xCC);

            g.setColor(bg);
            g.fillRect(0, 0, getWidth(), getHeight());

            // Right-side divider line
            g.setColor(div);
            g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(JsonFonts.small());
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();

            int w = getWidth() - 6; // right-align within ruler (leave 6px right margin)
            Rectangle clip = g.getClipBounds();
            if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());
            int rowCount = tree.getRowCount();

            for (int i = 0; i < rowCount; i++) {
                Rectangle bounds = tree.getRowBounds(i);
                if (bounds == null) continue;
                if (bounds.y + bounds.height < clip.y) continue;
                if (bounds.y > clip.y + clip.height) break;

                // Use the node's absolute index in the full tree
                TreePath path = tree.getPathForRow(i);
                Object node = path != null ? path.getLastPathComponent() : null;
                Integer abs = node != null ? nodeAbsIndex.get(node) : null;
                String num = String.valueOf(abs != null ? abs : i + 1);

                int x = w - fm.stringWidth(num);
                int y = bounds.y + (bounds.height + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(num, x, y);
            }
        }
    }

    // ── jq path builder ───────────────────────────────────────────────────────

    private String buildJqPath(TreePath path) {
        return JsonPaths.jq(path.getPath());
    }

    private String buildPythonPath(TreePath path) {
        return JsonPaths.python(path.getPath());
    }
}

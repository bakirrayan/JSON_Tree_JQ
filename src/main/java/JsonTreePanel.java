import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    private final ObjectMapper mapper = new ObjectMapper();

    // originalJson is set ONLY when a new HTTP response arrives — never overwritten by queries
    private String originalJson;

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
        tree.setFont(new Font("Roboto", Font.PLAIN, 17));

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
            btn.setFont(btn.getFont().deriveFont(Font.PLAIN, 11f));
            btn.setFocusPainted(false);
            btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        expandAll.addActionListener(e -> expandAll());
        collapseAll.addActionListener(e -> collapseAll());

        statusLabel.setFont(new Font("Roboto", Font.PLAIN, 11));
        pythonLabel.setFont(new Font("Roboto", Font.PLAIN, 11));

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

    /** Called when a new HTTP response arrives. This is the ONLY place originalJson is written. */
    public void setModel(DefaultTreeModel model, String originalJson) {
        this.originalJson = originalJson;
        applyModelToTree(model);
    }

    /** Rebuilds the tree from a JSON string. Does NOT touch originalJson. */
    public void rebuildTree(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            DefaultTreeModel model = JsonTreeModel.build(node);
            applyModelToTree(model);
        } catch (Exception ex) {
            searchBar.showError(ex.getMessage());
        }
    }

    // ── Tree display ──────────────────────────────────────────────────────────

    private void applyModelToTree(DefaultTreeModel model) {
        SwingUtilities.invokeLater(() -> {
            tree.setModel(model);
            rebuildNodeIndex();          // must happen after setModel
            // Auto-expand depth 1 only
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
            for (int i = tree.getRowCount() - 1; i > 0; i--) {
                TreePath path = tree.getPathForRow(i);
                if (path != null && path.getPathCount() > 2) tree.collapseRow(i);
            }
            searchBar.clearError();
        });
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

    private List<String> getSuggestions(String partialQuery) {
        if (originalJson == null || jqRunner == null) return List.of();

        int lastDot = partialQuery.lastIndexOf('.');
        if (lastDot < 0) return List.of();

        String pathPrefix = partialQuery.substring(0, lastDot);
        String filter     = partialQuery.substring(lastDot + 1);

        if (filter.contains("[") || filter.contains("|") || filter.contains(" ")) return List.of();

        try {
            JsonNode result;
            if (pathPrefix.isEmpty()) {
                result = mapper.readTree(originalJson);
            } else {
                result = mapper.readTree(jqRunner.run(originalJson, pathPrefix));
            }

            List<String> keys = new ArrayList<>();
            if (result.isObject()) {
                result.fieldNames().forEachRemaining(k -> {
                    if (filter.isEmpty() || k.startsWith(filter)) keys.add(k);
                });
            } else if (result.isArray() && result.size() > 0) {
                JsonNode first = result.get(0);
                if (first != null && first.isObject()) {
                    first.fieldNames().forEachRemaining(k -> {
                        if (filter.isEmpty() || k.startsWith(filter)) keys.add(k);
                    });
                }
            }
            return keys;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void runQuery() {
        String query = searchBar.getQuery();
        searchBar.clearError();

        if (originalJson == null) return;
        if (jqRunner == null) { searchBar.showError("jq engine failed to initialize"); return; }

        if (query.isEmpty() || query.equals(".")) {
            rebuildTree(originalJson);
            return;
        }

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override protected String doInBackground() throws Exception {
                return jqRunner.run(originalJson, query);
            }
            @Override protected void done() {
                try {
                    rebuildTree(get());
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

    private enum CopyMode { KEY, VALUE, KEY_VALUE }

    private void copySelected(CopyMode mode) {
        TreePath path = tree.getSelectionPath();
        if (path == null) return;
        Object last = path.getLastPathComponent();
        if (!(last instanceof JsonTreeNode node)) return;
        String text = switch (mode) {
            case KEY       -> node.getKey() != null ? node.getKey() : "";
            case VALUE     -> node.getValue();
            case KEY_VALUE -> (node.getKey() != null ? node.getKey() + ": " : "") + node.getValue();
        };
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

        menu.show(tree, e.getX(), e.getY());
    }

    // ── Line number ruler ─────────────────────────────────────────────────────

    private class LineNumberView extends JComponent {

        LineNumberView() {
            setFont(new Font("Roboto", Font.PLAIN, 11));
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
            FontMetrics fm = getFontMetrics(getFont());
            return fm.stringWidth("9".repeat(String.valueOf(maxNum).length())) + 14;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(rulerWidth(), tree.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            // Theme-aware colours
            boolean dark = JsonTreeCellRenderer.isDark();
            Color bg  = dark ? new Color(0x2D, 0x2D, 0x2D) : new Color(0xF5, 0xF5, 0xF5);
            Color fg  = dark ? new Color(0x66, 0x66, 0x66) : new Color(0x99, 0x99, 0x99);
            Color div = dark ? new Color(0x44, 0x44, 0x44) : new Color(0xCC, 0xCC, 0xCC);

            setBackground(bg);
            setForeground(fg);
            super.paintComponent(g);

            // Right-side divider line
            g.setColor(div);
            g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());
            g2.setColor(fg);
            FontMetrics fm = g2.getFontMetrics();

            int w = getWidth() - 6; // right-align within ruler (leave 6px right margin)
            Rectangle clip = g.getClipBounds();
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
        StringBuilder sb = new StringBuilder();
        Object[] components = path.getPath();
        for (int i = 1; i < components.length; i++) {
            if (components[i] instanceof JsonTreeNode node) {
                String key = node.getKey();
                if (key != null && key.startsWith("[") && key.endsWith("]")) {
                    sb.append(key);
                } else if (key != null) {
                    sb.append(".").append(key);
                }
            }
        }
        return sb.length() == 0 ? "." : sb.toString();
    }

    private String buildPythonPath(TreePath path) {
        StringBuilder sb = new StringBuilder("response.json()");
        Object[] components = path.getPath();
        for (int i = 1; i < components.length; i++) {
            if (components[i] instanceof JsonTreeNode node) {
                String key = node.getKey();
                if (key != null && key.startsWith("[") && key.endsWith("]")) {
                    sb.append(key); // array index: [0]
                } else if (key != null) {
                    sb.append("['").append(key).append("']"); // object key: ['name']
                }
            }
        }
        return sb.toString();
    }
}

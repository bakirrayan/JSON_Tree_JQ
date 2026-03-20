import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.function.Function;

public class JqSearchBar extends JPanel {

    private static final int   DEBOUNCE_MS = 400;
    private static final Color ERROR_COLOR = new Color(0xD9, 0x53, 0x4F);
    private static final Color HINT_COLOR  = new Color(0x99, 0x99, 0x99);
    private static final Font  MONO        = new Font("Roboto", Font.PLAIN, 13);

    private final JTextField   queryField;
    private final JLabel       errorLabel = new JLabel(" ");
    private final ActionListener onRun;
    private final Timer        debounceTimer;

    // Suggestion popup
    private JWindow                      popupWindow;
    private final DefaultListModel<String> listModel      = new DefaultListModel<>();
    private final JList<String>           suggestionList  = new JList<>(listModel);
    private Function<String, List<String>> suggestionProvider;

    public JqSearchBar(ActionListener onRun) {
        this.onRun = onRun;
        setLayout(new BorderLayout(0, 2));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));

        // Query field with placeholder hint
        queryField = new JTextField() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && !isFocusOwner()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(HINT_COLOR);
                    g2.setFont(MONO.deriveFont(Font.ITALIC));
                    Insets ins = getInsets();
                    g2.drawString("jq filter  (e.g. .user.name)", ins.left + 2, getHeight() - ins.bottom - 4);
                    g2.dispose();
                }
            }
        };
        queryField.setFont(MONO);
        queryField.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(new Color(0x99, 0x99, 0x99)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));

        errorLabel.setFont(MONO.deriveFont(Font.PLAIN, 11f));
        errorLabel.setForeground(ERROR_COLOR);

        JPanel inputRow = new JPanel(new BorderLayout(6, 0));
        inputRow.add(queryField, BorderLayout.CENTER);

        add(inputRow, BorderLayout.CENTER);
        add(errorLabel, BorderLayout.SOUTH);

        // Debounce timer — fires query after user stops typing
        debounceTimer = new Timer(DEBOUNCE_MS,
                e -> onRun.actionPerformed(new ActionEvent(queryField, ActionEvent.ACTION_PERFORMED, "debounce")));
        debounceTimer.setRepeats(false);

        // Live typing → restart debounce + refresh suggestions
        queryField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onChange(); }
            @Override public void removeUpdate(DocumentEvent e) { onChange(); }
            @Override public void changedUpdate(DocumentEvent e) { onChange(); }
        });

        // Enter key: run immediately
        queryField.addActionListener(e -> {
            debounceTimer.stop();
            hideSuggestions();
            onRun.actionPerformed(e);
        });

        // Arrow / Enter / Escape navigation when popup is visible
        queryField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (popupWindow == null || !popupWindow.isVisible()) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        int i = suggestionList.getSelectedIndex();
                        suggestionList.setSelectedIndex(Math.min(i + 1, listModel.size() - 1));
                        e.consume();
                    }
                    case KeyEvent.VK_UP -> {
                        int i = suggestionList.getSelectedIndex();
                        if (i > 0) suggestionList.setSelectedIndex(i - 1);
                        e.consume();
                    }
                    case KeyEvent.VK_ENTER -> {
                        String sel = suggestionList.getSelectedValue();
                        if (sel != null) { applySuggestion(sel); e.consume(); }
                    }
                    case KeyEvent.VK_ESCAPE -> {
                        hideSuggestions();
                        e.consume();
                    }
                }
            }
        });

        // Hide popup on focus loss (with small delay so mouse click on list registers first)
        queryField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { queryField.repaint(); }
            @Override public void focusLost(FocusEvent e) {
                queryField.repaint();
                Timer t = new Timer(150, ev -> hideSuggestions());
                t.setRepeats(false);
                t.start();
            }
        });

        // Suggestion list styling
        suggestionList.setFont(MONO);
        suggestionList.setFixedCellHeight(22);
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestionList.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        suggestionList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                String sel = suggestionList.getSelectedValue();
                if (sel != null) applySuggestion(sel);
            }
        });
    }

    private void onChange() {
        debounceTimer.restart();
        SwingUtilities.invokeLater(this::updateSuggestions);
    }

    private void updateSuggestions() {
        if (suggestionProvider == null) return;
        List<String> suggestions = suggestionProvider.apply(queryField.getText());
        if (suggestions.isEmpty()) { hideSuggestions(); return; }

        listModel.clear();
        suggestions.forEach(listModel::addElement);
        suggestionList.setSelectedIndex(0);

        // Create popup window lazily (needs a parent window)
        if (popupWindow == null) {
            Window parent = SwingUtilities.getWindowAncestor(queryField);
            if (parent == null) return;
            popupWindow = new JWindow(parent);
            popupWindow.setFocusableWindowState(false);
            JScrollPane sp = new JScrollPane(suggestionList);
            sp.setBorder(BorderFactory.createLineBorder(new Color(0x77, 0x99, 0xCC)));
            popupWindow.add(sp);
        }

        try {
            Point loc = queryField.getLocationOnScreen();
            int rows = Math.min(listModel.size(), 6);
            popupWindow.setBounds(loc.x, loc.y + queryField.getHeight(),
                    queryField.getWidth(), rows * 22 + 4);
            popupWindow.setVisible(true);
        } catch (IllegalComponentStateException ignored) {}
    }

    private void hideSuggestions() {
        if (popupWindow != null) popupWindow.setVisible(false);
    }

    private void applySuggestion(String suggestion) {
        String current = queryField.getText();
        int lastDot = current.lastIndexOf('.');
        String newText = (lastDot >= 0)
                ? current.substring(0, lastDot + 1) + suggestion
                : "." + suggestion;
        queryField.setText(newText);
        queryField.setCaretPosition(newText.length());
        hideSuggestions();
        queryField.requestFocusInWindow();
    }

    public void setSuggestionProvider(Function<String, List<String>> provider) {
        this.suggestionProvider = provider;
    }

    public String getQuery() { return queryField.getText().trim(); }
    public void clearError()  { errorLabel.setText(" "); }
    public void showError(String msg) { errorLabel.setText(msg); }
}

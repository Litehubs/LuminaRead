package com.luminaread.ui;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

final class ReaderSearchBar extends JPanel {
    private final JTextField field = new JTextField(16);
    private final JLabel label = new JLabel("Поиск в книге:");
    private final JButton previous;
    private final JButton next;
    private final JLabel status = new JLabel();

    ReaderSearchBar(ThemePalette theme, Consumer<ReaderTextSearch.Direction> search, Runnable clear) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);

        label.setLabelFor(field);
        previous = ModernUiFactory.createFlatButton("↑", theme);
        next = ModernUiFactory.createFlatButton("↓", theme);
        previous.setToolTipText("Предыдущее совпадение — Shift+Enter");
        next.setToolTipText("Следующее совпадение — Enter");
        previous.getAccessibleContext().setAccessibleName("Предыдущее совпадение");
        next.getAccessibleContext().setAccessibleName("Следующее совпадение");
        previous.addActionListener(e -> search.accept(ReaderTextSearch.Direction.BACKWARD));
        next.addActionListener(e -> search.accept(ReaderTextSearch.Direction.FORWARD));
        field.addActionListener(e -> search.accept(ReaderTextSearch.Direction.FORWARD));
        field.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "previousMatch");
        field.getActionMap().put("previousMatch", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                search.accept(ReaderTextSearch.Direction.BACKWARD);
            }
        });
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { clear.run(); }
            @Override public void removeUpdate(DocumentEvent e) { clear.run(); }
            @Override public void changedUpdate(DocumentEvent e) { clear.run(); }
        });
        add(label);
        add(field);
        add(previous);
        add(next);
        add(status);
        applyTheme(theme);
    }

    String query() { return field.getText(); }
    boolean hasFieldFocus() { return field.hasFocus(); }

    void focusField() {
        field.requestFocusInWindow();
        field.selectAll();
    }

    void setStatus(String text) { status.setText(text); }

    void applyTheme(ThemePalette theme) {
        ModernUiFactory.styleTextField(field, theme);
        label.setForeground(theme.getTextSecondary());
        status.setForeground(theme.getTextPrimary());
        ModernUiFactory.styleFlatButton(previous, theme);
        ModernUiFactory.styleFlatButton(next, theme);
    }
}

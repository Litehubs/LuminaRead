package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class MangaSettingsViewTest {
    @Test void savedMaximumValuesCanOpenSettings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var actions = new MangaSettingsView.Actions(v -> {}, v -> {}, (v, c) -> {}, v -> {}, v -> {}, v -> {}, v -> {}, v -> {});
            var panel = MangaSettingsView.create(new MangaSettingsView.State(0, 1, 1800, 0, 180, 0, false), actions, () -> {});
            assertEquals(1800, find(panel, JSlider.class).get(0).getValue());
            assertEquals(180, find(panel, JSlider.class).get(1).getValue());
        });
    }
    @Test void settingsControlsNotifyTheirOwner() throws Exception {
        AtomicInteger mode = new AtomicInteger(-1), alignment = new AtomicInteger(-1), width = new AtomicInteger(-1);
        AtomicInteger gap = new AtomicInteger(-1), speed = new AtomicInteger(-1), brightness = new AtomicInteger(-1);
        AtomicBoolean forceCenter = new AtomicBoolean(), hide = new AtomicBoolean();
        AtomicReference<Color> color = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var state = new MangaSettingsView.State(0, 1, 860, 0, 45, 0.0, false);
            var actions = new MangaSettingsView.Actions(mode::set, alignment::set, (v, center) -> { width.set(v); forceCenter.set(center); },
                    gap::set, speed::set, brightness::set, color::set, hide::set);
            JPanel panel = MangaSettingsView.create(state, actions, () -> {});
            find(panel, JRadioButton.class).stream().filter(b -> b.getText().startsWith("📖")).findFirst().orElseThrow().doClick();
            find(panel, JRadioButton.class).stream().filter(b -> b.getText().equals("По ширине окна")).findFirst().orElseThrow().doClick();
            find(panel, JRadioButton.class).stream().filter(b -> b.getText().equals("8 px")).findFirst().orElseThrow().doClick();
            List<JSlider> sliders = find(panel, JSlider.class);
            sliders.get(1).setValue(75); sliders.get(2).setValue(70);
            find(panel, JButton.class).stream().filter(b -> b.getText().equals("1050 px")).findFirst().orElseThrow().doClick();
            find(panel, JButton.class).stream().filter(b -> b.getText().contains("Сепия")).findFirst().orElseThrow().doClick();
            find(panel, JCheckBox.class).stream().filter(b -> b.getText().startsWith("Скрывать")).findFirst().orElseThrow().doClick();
        });
        assertEquals(1, mode.get()); assertEquals(2, alignment.get()); assertEquals(1050, width.get()); assertTrue(forceCenter.get());
        assertEquals(8, gap.get()); assertEquals(75, speed.get()); assertEquals(70, brightness.get());
        assertEquals(new Color(42, 34, 28), color.get()); assertTrue(hide.get());
    }

    private static <T extends Component> List<T> find(Container root, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container nested) result.addAll(find(nested, type));
        }
        return result;
    }
}

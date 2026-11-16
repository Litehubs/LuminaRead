package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RanobeSettingsViewTest {
    @Test void savedExtremeValuesCanOpenSettings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var actions = new RanobeSettingsView.Actions(v -> {}, v -> {}, v -> {}, v -> {}, v -> {}, v -> {});
            var panel = RanobeSettingsView.create(new RanobeSettingsView.State(0, 12, "Georgia", 1600, 180), actions, () -> {});
            assertEquals(12, find(panel, JSlider.class).get(0).getValue());
            assertEquals(1600, find(panel, JSlider.class).get(1).getValue());
            assertEquals(180, find(panel, JSlider.class).get(2).getValue());
        });
    }
    @Test void controlsSendChangesToTheirOwner() throws Exception {
        AtomicInteger mode = new AtomicInteger(-1), size = new AtomicInteger(-1), width = new AtomicInteger(-1), speed = new AtomicInteger(-1);
        AtomicReference<String> font = new AtomicReference<>(), theme = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var state = new RanobeSettingsView.State(0, 19, "Georgia", 840, 40);
            var actions = new RanobeSettingsView.Actions(mode::set, size::set, font::set, width::set, theme::set, speed::set);
            JPanel panel = RanobeSettingsView.create(state, actions, () -> {});
            List<JSlider> sliders = find(panel, JSlider.class);
            sliders.get(0).setValue(22); sliders.get(1).setValue(900); sliders.get(2).setValue(60);
            find(panel, JComboBox.class).get(0).setSelectedItem("Verdana");
            find(panel, JRadioButton.class).get(1).doClick();
            find(panel, JButton.class).stream().filter(b -> b.getText().contains("Сепия")).findFirst().orElseThrow().doClick();
        });
        assertEquals(1, mode.get()); assertEquals(22, size.get()); assertEquals(900, width.get()); assertEquals(60, speed.get());
        assertEquals("Verdana", font.get()); assertEquals("SEPIA", theme.get());
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

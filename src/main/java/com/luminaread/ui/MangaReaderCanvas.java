package com.luminaread.ui;

import javax.swing.*;
import java.awt.*;

final class MangaReaderCanvas extends JPanel implements Scrollable {
    MangaReaderCanvas() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 64;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return 250;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {

        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}

package com.luminaread.util;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;

public final class SystemBrowser {
    private SystemBrowser() {}

    public static void open(URI address) throws IOException {
        try {
            if (!"https".equals(address.getScheme()) || GraphicsEnvironment.isHeadless()
                    || !Desktop.isDesktopSupported()) {
                throw new IOException("Браузер недоступен.");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) throw new IOException("Браузер недоступен.");
            desktop.browse(address);
        } catch (IOException | UnsupportedOperationException | SecurityException ex) {
            // Desktop failures can include the URL and its OAuth state.
            throw new IOException("Не удалось открыть браузер.");
        }
    }
}

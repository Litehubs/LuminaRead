package com.luminaread.ui;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Loads a page off the event thread and discards results from an obsolete reader state. */
final class MangaPageLoader {
    private static final Logger LOG = Logger.getLogger(MangaPageLoader.class.getName());
    private final Executor executor;
    private final Function<String, BufferedImage> loadImage;
    private final IntPredicate isCurrent;

    MangaPageLoader(Executor executor, Function<String, BufferedImage> loadImage, IntPredicate isCurrent) {
        this.executor = Objects.requireNonNull(executor);
        this.loadImage = Objects.requireNonNull(loadImage);
        this.isCurrent = Objects.requireNonNull(isCurrent);
    }

    void load(String url, int token, Consumer<BufferedImage> completion) {
        Objects.requireNonNull(completion);
        if (!isCurrent.test(token)) return;
        try {
            executor.execute(() -> {
                if (!isCurrent.test(token)) return;
                BufferedImage result;
                try {
                    result = loadImage.apply(url);
                } catch (RuntimeException ex) {
                    LOG.log(Level.WARNING, "Не удалось загрузить страницу манги", ex);
                    result = null;
                }
                if (!isCurrent.test(token)) return;
                BufferedImage image = result;
                SwingUtilities.invokeLater(() -> {
                    if (isCurrent.test(token)) completion.accept(image);
                });
            });
        } catch (RejectedExecutionException ex) {
            if (isCurrent.test(token)) {
                LOG.log(Level.WARNING, "Не удалось запустить загрузку страницы манги", ex);
                SwingUtilities.invokeLater(() -> {
                    if (isCurrent.test(token)) completion.accept(null);
                });
            }
        }
    }
}

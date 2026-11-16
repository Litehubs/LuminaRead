package com.luminaread.ui;

import java.awt.CardLayout;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;

final class MotionCardPanel extends MotionSurface {
    private final CardLayout cards = new CardLayout();
    private final Map<String, Integer> cardOrder = new LinkedHashMap<>();
    private final String readingCard;
    private String currentCard;
    private MotionSurface transitionSurface = this;

    MotionCardPanel(String readingCard) {
        this.readingCard = readingCard;
        setLayout(cards);
    }

    void addCard(String key, Component component) {
        if (cardOrder.putIfAbsent(key, cardOrder.size()) != null) {
            throw new IllegalArgumentException("Duplicate card: " + key);
        }
        add(component, key);
        if (currentCard == null) currentCard = key;
    }

    void useTransitionSurface(MotionSurface surface) {
        cancelTransition();
        transitionSurface = surface;
    }

    void showCard(String key) {
        showCard(key, () -> {});
    }

    void showCard(String key, Runnable updateLayout) {
        Integer target = cardOrder.get(key);
        if (target == null) return;
        if (key.equals(currentCard)) {
            updateLayout.run();
            return;
        }

        Effect effect = key.equals(readingCard) ? Effect.OPEN
                : currentCard.equals(readingCard) ? Effect.CLOSE : Effect.SLIDE;
        int direction = Integer.compare(target, cardOrder.get(currentCard));
        transitionSurface.change(effect, direction, () -> {
            updateLayout.run();
            cards.show(this, key);
            currentCard = key;
        });
    }
}

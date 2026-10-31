package com.luminaread.model;

import java.io.Serializable;

public class Chapter implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int index;
    private final String title;
    private final String htmlContent;
    private final String plainText;
    private final int wordCount;

    public Chapter(int index, String title, String htmlContent, String plainText) {
        this.index = index;
        this.title = (title == null || title.isBlank()) ? ("Глава " + (index + 1)) : title.trim();
        this.htmlContent = htmlContent != null ? htmlContent : "";
        this.plainText = plainText != null ? plainText : "";
        this.wordCount = countWords(this.plainText);
    }

    private static int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0;
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isLetterOrDigit(trimmed.charAt(i))) {
                if (!inWord) {
                    inWord = true;
                    count++;
                }
            } else {
                inWord = false;
            }
        }
        return count;
    }

    public int getIndex() { return index; }
    public String getTitle() { return title; }
    public String getHtmlContent() { return htmlContent; }
    public String getPlainText() { return plainText; }
    public int getWordCount() { return wordCount; }

    @Override
    public String toString() {
        return title;
    }
}

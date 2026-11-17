package com.luminaread.service;

import com.luminaread.model.Bookmark;
import com.luminaread.model.Highlight;

import java.util.List;

final class NotesMarkdown {
    private NotesMarkdown() {}

    static String render(List<Highlight> highlights, List<Bookmark> bookmarks) {
        StringBuilder md = new StringBuilder("# Экспорт цитат, заметок и закладок — LuminaRead\n\n");
        md.append("## Выделенные цитаты и заметки (").append(highlights.size()).append(")\n\n");
        if (highlights.isEmpty()) md.append("_Нет сохраненных цитат._\n\n");
        for (Highlight highlight : highlights) {
            md.append("### ").append(inline(highlight.getBookTitle())).append(" — ")
                    .append(inline(highlight.getChapterTitle())).append("\n\n");
            quote(md, highlight.getSelectedText());
            if (!highlight.getNote().isBlank()) {
                md.append("**Заметка:**\n\n");
                quote(md, highlight.getNote());
            }
            md.append("*Дата: ").append(inline(highlight.getFormattedDate())).append("*\n\n");
        }

        md.append("## Закладки (").append(bookmarks.size()).append(")\n\n");
        if (bookmarks.isEmpty()) md.append("_Нет сохраненных закладок._\n");
        for (Bookmark bookmark : bookmarks) {
            md.append("* **").append(inline(bookmark.getBookTitle())).append("** (")
                    .append(inline(bookmark.getChapterTitle())).append(", стр. ")
                    .append(bookmark.getPageIndex() + 1).append(") — ")
                    .append(inline(bookmark.getLabel())).append(" _(")
                    .append(inline(bookmark.getFormattedDate())).append(")_\n");
        }
        return md.toString();
    }

    private static void quote(StringBuilder md, String text) {
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            md.append('>');
            if (!line.isBlank()) {
                md.append(' ').append(escape(line.strip()));
                if (index + 1 < lines.length && !lines[index + 1].isBlank()) md.append('\\');
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static String inline(String text) {
        return escape(text.replaceAll("\\R+", " ").strip());
    }

    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                default -> {
                    if ("\\§*_{}[]()#+-.!|~:@=".indexOf(c) >= 0) escaped.append('\\');
                    escaped.append(c);
                }
            }
        }
        return escaped.toString();
    }
}

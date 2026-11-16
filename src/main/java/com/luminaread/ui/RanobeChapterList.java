package com.luminaread.ui;

import com.luminaread.model.ranobe.RanobeChapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Filtering, ordering and selection of a title's chapters, independent of Swing. */
final class RanobeChapterList {
    private List<RanobeChapter> all = List.of();
    private List<RanobeChapter> visible = List.of();
    private boolean ascending = true;

    void clear() { all = List.of(); visible = List.of(); }

    void setChapters(List<RanobeChapter> chapters) {
        all = List.copyOf(chapters);
        filter("");
    }

    void filter(String query) {
        String clean = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        List<RanobeChapter> result = new ArrayList<>();
        for (RanobeChapter chapter : all) {
            if (clean.isEmpty() || chapter.number.contains(clean)
                    || chapter.title.toLowerCase(Locale.ROOT).contains(clean)) result.add(chapter);
        }
        if (!ascending) Collections.reverse(result);
        visible = List.copyOf(result);
    }

    void toggleOrder() {
        ascending = !ascending;
        List<RanobeChapter> reversed = new ArrayList<>(visible);
        Collections.reverse(reversed);
        visible = List.copyOf(reversed);
    }

    boolean isAscending() { return ascending; }
    List<RanobeChapter> visible() { return visible; }
    int lastReadIndex(String progress) {
        if (progress != null && !progress.isEmpty()) {
            for (int i = 0; i < visible.size(); i++) {
                if (progress.equals("Гл. " + visible.get(i).number)) return i;
            }
        }
        return 0;
    }
}

package com.luminaread.model.ranobe;

import java.util.Collections;
import java.util.List;

/**
 * Domain model representing the retrieved content of a Ranobe chapter.
 */
public class RanobeChapterContent {

    public final String title;
    public final String volume;
    public final String number;
    public final String rawHtmlContent;
    public final String plainTextContent;
    public final List<String> illustrations;

    public RanobeChapterContent(String title, String volume, String number,
                                String rawHtmlContent, String plainTextContent, List<String> illustrations) {
        this.title = title != null ? title : "";
        this.volume = volume != null ? volume : "1";
        this.number = number != null ? number : "1";
        this.rawHtmlContent = rawHtmlContent != null ? rawHtmlContent : "";
        this.plainTextContent = plainTextContent != null ? plainTextContent : "";
        this.illustrations = illustrations != null ? Collections.unmodifiableList(illustrations) : Collections.emptyList();
    }
}

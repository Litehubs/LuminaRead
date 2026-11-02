package com.luminaread.model.ranobe;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain model representing a Ranobe (light novel) title.
 */
public class RanobeItem {

    public final String id;
    public final String title;
    public final String rusTitle;
    public final String description;
    public final String coverUrl;
    public final String status;
    public final String rating;
    public final String releaseDate;
    public final List<String> genres;
    public final List<String> authors;

    public RanobeItem(String id, String title, String rusTitle, String description,
                      String coverUrl, String status, String rating, String releaseDate,
                      List<String> genres, List<String> authors) {
        this.id = id != null ? id : "";
        this.title = title != null ? title : "";
        this.rusTitle = rusTitle != null ? rusTitle : "";
        this.description = description != null ? description : "";
        this.coverUrl = coverUrl != null ? coverUrl : "";
        this.status = status != null ? status : "";
        this.rating = rating != null ? rating : "★ —";
        this.releaseDate = releaseDate != null ? releaseDate : "";
        this.genres = genres != null ? Collections.unmodifiableList(genres) : Collections.emptyList();
        this.authors = authors != null ? Collections.unmodifiableList(authors) : Collections.emptyList();
    }

    public String getDisplayTitle() {
        return (!rusTitle.isEmpty()) ? rusTitle + " (" + title + ")" : title;
    }

    public String getGenresString() {
        return String.join(", ", genres);
    }

    public String getAuthorsString() {
        return String.join(", ", authors);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RanobeItem)) return false;
        RanobeItem that = (RanobeItem) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return getDisplayTitle();
    }
}

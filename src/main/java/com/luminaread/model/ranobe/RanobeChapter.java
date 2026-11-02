package com.luminaread.model.ranobe;

import java.util.Objects;

/**
 * Domain model representing a chapter in a Ranobe title.
 */
public class RanobeChapter {

    public final String id;
    public final String volume;
    public final String number;
    public final String title;
    public final String branchId;
    public final String teamName;

    public RanobeChapter(String id, String volume, String number, String title, String branchId, String teamName) {
        this.id = id != null ? id : "";
        this.volume = volume != null ? volume : "1";
        this.number = number != null ? number : "1";
        this.title = title != null ? title : "";
        this.branchId = branchId != null ? branchId : "";
        this.teamName = teamName != null ? teamName : "";
    }

    @Override
    public String toString() {
        String numStr = "Том " + volume + ", Глава " + number;
        String tStr = (!title.isEmpty()) ? " — " + title : "";
        String teamStr = (!teamName.isEmpty()) ? " [" + teamName + "]" : "";
        return numStr + tStr + teamStr;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RanobeChapter)) return false;
        RanobeChapter that = (RanobeChapter) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

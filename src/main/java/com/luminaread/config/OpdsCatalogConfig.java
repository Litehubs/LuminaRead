package com.luminaread.config;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OpdsCatalogConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final List<String> DEFAULT_CATALOGS = List.of(
            "http://flibusta.is/opds",
            "https://www.gutenberg.org/ebooks/search.opds/",
            "https://standardebooks.org/opds/all"
    );

    private final List<String> catalogs = new ArrayList<>();

    public OpdsCatalogConfig() {
        catalogs.addAll(DEFAULT_CATALOGS);
    }

    public synchronized List<String> getCustomOpdsCatalogs() {
        return Collections.unmodifiableList(new ArrayList<>(catalogs));
    }

    public synchronized boolean addCatalog(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String normalized = url.trim();
        if (!catalogs.contains(normalized)) {
            catalogs.add(normalized);
            return true;
        }
        return false;
    }

    public synchronized boolean removeCatalog(String url) {
        if (url == null) return false;
        return catalogs.remove(url.trim());
    }

    public synchronized void resetDefaults() {
        catalogs.clear();
        catalogs.addAll(DEFAULT_CATALOGS);
    }

    public synchronized void setCatalogs(List<String> newCatalogs) {
        catalogs.clear();
        if (newCatalogs != null) {
            for (String c : newCatalogs) {
                if (c != null && !c.isBlank() && !catalogs.contains(c.trim())) {
                    catalogs.add(c.trim());
                }
            }
        }
        if (catalogs.isEmpty()) {
            catalogs.addAll(DEFAULT_CATALOGS);
        }
    }
}

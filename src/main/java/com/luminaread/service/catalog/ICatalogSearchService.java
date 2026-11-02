package com.luminaread.service.catalog;

import com.luminaread.model.OnlineBookItem;

import java.util.List;

/**
 * Interface segregation for catalog search and feed browsing operations.
 */
public interface ICatalogSearchService {

    List<OnlineBookItem> searchBooks(String query, String sourceFilter, String languageFilter);

    List<OnlineBookItem> browseCustomOpds(String opdsUrl, String optionalSearchTerm);

    List<OnlineBookItem> fetchOpdsFeed(String feedUrl, String sourceLabel);
}

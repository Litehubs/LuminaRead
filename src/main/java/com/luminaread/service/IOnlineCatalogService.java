package com.luminaread.service;

import com.luminaread.service.catalog.IBookDownloader;
import com.luminaread.service.catalog.ICatalogSearchService;
import com.luminaread.service.catalog.IDownloadManager;

/**
 * Composite online catalog service interface combining search, download management, and book downloading.
 * Maintained for backward compatibility with existing callers.
 */
public interface IOnlineCatalogService extends ICatalogSearchService, IDownloadManager, IBookDownloader {
}

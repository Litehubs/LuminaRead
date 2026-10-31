package com.luminaread.service;

import java.awt.image.BufferedImage;
import java.util.List;

public interface IMediaCacheService {

    class CachedImageInfo {
        public final String localUrl;
        public final int width;
        public final int height;

        public CachedImageInfo(String localUrl, int width, int height) {
            this.localUrl = localUrl != null ? localUrl : "";
            this.width = width;
            this.height = height;
        }

        public String getLocalUrl() {
            return localUrl;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        @Override
        public String toString() {
            return "CachedImageInfo{localUrl='" + localUrl + "', width=" + width + ", height=" + height + "}";
        }
    }

    BufferedImage loadImageCached(String urlStr, List<String> failoverServers);

    default BufferedImage loadImageCached(String urlStr) {
        return loadImageCached(urlStr, null);
    }

    CachedImageInfo cacheImageToDisk(String urlStr, String subDir, String referer, int targetMaxWidth);

    BufferedImage downloadAndDecode(String urlStr, String referer);

    default BufferedImage downloadAndDecode(String urlStr) {
        return downloadAndDecode(urlStr, null);
    }

    void clearMemoryCache();
}

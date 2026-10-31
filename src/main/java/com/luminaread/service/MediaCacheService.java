package com.luminaread.service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MediaCacheService implements IMediaCacheService {

    private static final MediaCacheService INSTANCE = new MediaCacheService();

    public static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    public static final String RANOBE_ORIGIN = "https://ranobelib.me";
    public static final String MGL_ORIGIN = "https://mangalib.me";
    public static final String REMANGA_ORIGIN = "https://remanga.org";
    public static final String MANGADEX_ORIGIN = "https://mangadex.org";

    public static final List<String> DEFAULT_MGL_IMAGE_SERVERS = Collections.unmodifiableList(Arrays.asList(
            "https://img3.cdnrulib.link",
            "https://img3.cdnlibs.org",
            "https://img4.cdnrulib.link",
            "https://img2.imglib.info",
            "https://img3.hentaicdn.org",
            "https://img2.hentaicdn.org"
    ));

    private final Map<String, BufferedImage> memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<String, BufferedImage>(70, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                    return size() > 70;
                }
            }
    );

    public MediaCacheService() {
    }

    public static MediaCacheService getInstance() {
        return INSTANCE;
    }

    @Override
    public BufferedImage loadImageCached(String urlStr, List<String> failoverServers) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            return null;
        }
        urlStr = urlStr.trim();

        if (memoryCache.containsKey(urlStr)) {
            return memoryCache.get(urlStr);
        }

        BufferedImage img = downloadAndDecode(urlStr, null);
        if (img != null) {
            memoryCache.put(urlStr, img);
            return img;
        }

        List<String> serversToTry = failoverServers;
        if ((serversToTry == null || serversToTry.isEmpty()) && isMangaLibUrl(urlStr)) {
            serversToTry = DEFAULT_MGL_IMAGE_SERVERS;
        }

        if (serversToTry != null) {
            for (String altServer : serversToTry) {
                String altUrl = replaceHost(urlStr, altServer);
                if (altUrl != null && !altUrl.equals(urlStr)) {
                    BufferedImage altImg = downloadAndDecode(altUrl, null);
                    if (altImg != null) {
                        memoryCache.put(urlStr, altImg);
                        return altImg;
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void clearMemoryCache() {
        memoryCache.clear();
    }

    @Override
    public CachedImageInfo cacheImageToDisk(String urlStr, String subDir, String referer, int targetMaxWidth) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            return new CachedImageInfo("", 0, 0);
        }
        urlStr = urlStr.trim();

        String folderName = (subDir != null && !subDir.trim().isEmpty()) ? subDir.trim() : "images";
        File cacheDir = java.nio.file.Path.of(System.getProperty("user.home"), ".luminaread", "cache", folderName).toFile();
        try {
            Files.createDirectories(cacheDir.toPath());
        } catch (Exception ignored) {}

        String hash = hashUrl(urlStr);
        File cachedPng = new File(cacheDir, "img_" + hash + ".png");

        if (cachedPng.exists() && cachedPng.length() > 0) {
            try {
                BufferedImage bi = ImageIO.read(cachedPng);
                if (bi != null) {
                    return new CachedImageInfo(cachedPng.toURI().toString(), bi.getWidth(), bi.getHeight());
                }
            } catch (Throwable ignored) {
            }
            if (cachedPng.length() > 500) {
                return new CachedImageInfo(cachedPng.toURI().toString(), 0, 0);
            }
        }
        if (false) {
            try {
                BufferedImage bi = ImageIO.read(cachedPng);
                if (bi != null) {
                    return new CachedImageInfo(cachedPng.toURI().toString(), bi.getWidth(), bi.getHeight());
                }
            } catch (Throwable ignored) {
            }
            return new CachedImageInfo(cachedPng.toURI().toString(), 0, 0);
        }

        byte[] bytes = downloadImageBytes(urlStr, referer);
        if (bytes != null && bytes.length > 0) {
            BufferedImage bi = decodeBytes(bytes);

            if (bi != null) {

                if (targetMaxWidth > 0 && bi.getWidth() > targetMaxWidth) {
                    int w = bi.getWidth();
                    int h = bi.getHeight();
                    int newH = (int) Math.round((double) h * targetMaxWidth / w);
                    BufferedImage scaled = new BufferedImage(targetMaxWidth, Math.max(1, newH), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = scaled.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.drawImage(bi, 0, 0, targetMaxWidth, newH, null);
                    g2.dispose();
                    bi = scaled;
                }

                try {
                    ImageIO.write(bi, "png", cachedPng);
                    return new CachedImageInfo(cachedPng.toURI().toString(), bi.getWidth(), bi.getHeight());
                } catch (Throwable e) {
                    System.err.println("Error saving cached image to " + cachedPng.getAbsolutePath() + ": " + e.getMessage());
                }
            } else {
                try {
                    Files.write(cachedPng.toPath(), bytes);
                    return new CachedImageInfo(cachedPng.toURI().toString(), 0, 0);
                } catch (Throwable ignored) {
                }
            }
        }

        return new CachedImageInfo(urlStr, 0, 0);
    }

    @Override
    public BufferedImage downloadAndDecode(String urlStr, String referer) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = downloadImageBytes(urlStr.trim(), referer);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return decodeBytes(bytes);
        } catch (Exception ignored) {
        }
        return null;
    }

    public BufferedImage decodeBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi != null && bi.getWidth() > 0 && bi.getHeight() > 0) {
                return bi;
            }
        } catch (Throwable ignored) {
        }

        try {
            ImageIO.scanForPlugins();
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi != null && bi.getWidth() > 0 && bi.getHeight() > 0) {
                return bi;
            }
        } catch (Throwable ignored) {
        }

        BufferedImage sipsImg = tryDecodeWithSips(bytes);
        if (sipsImg != null && sipsImg.getWidth() > 0 && sipsImg.getHeight() > 0) {
            return sipsImg;
        }

        try {
            Image awtImg = Toolkit.getDefaultToolkit().createImage(bytes);
            if (awtImg != null) {
                BufferedImage bi = toBufferedImage(awtImg);
                if (bi != null && bi.getWidth() > 0 && bi.getHeight() > 0) {
                    return bi;
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    public byte[] downloadImageBytes(String urlStr, String referer) {
        HttpURLConnection conn = null;
        try {
            URL url = URI.create(urlStr).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/png,image/jpeg,image/*,*/*;q=0.8");

            String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.ROOT);
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
                try {
                    URI refUri = URI.create(referer);
                    if (refUri.getScheme() != null && refUri.getHost() != null) {
                        conn.setRequestProperty("Origin", refUri.getScheme() + "://" + refUri.getHost());
                    }
                } catch (Exception ignored) {
                }
            } else {
                if (isMangaLibUrl(host) || isMangaLibUrl(urlStr)) {
                    conn.setRequestProperty("Referer", MGL_ORIGIN + "/");
                    conn.setRequestProperty("Origin", MGL_ORIGIN);
                } else if (host.contains("remanga") || host.contains("reimg")) {
                    conn.setRequestProperty("Referer", REMANGA_ORIGIN + "/");
                    conn.setRequestProperty("Origin", REMANGA_ORIGIN);
                } else if (host.contains("mangadex")) {
                    conn.setRequestProperty("Referer", MANGADEX_ORIGIN + "/");
                } else if (host.contains("ranobelib")) {
                    conn.setRequestProperty("Referer", RANOBE_ORIGIN + "/");
                    conn.setRequestProperty("Origin", RANOBE_ORIGIN);
                }
            }

            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream is = conn.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        baos.write(buf, 0, r);
                    }
                    return baos.toByteArray();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    public static BufferedImage tryDecodeWithSips(byte[] bytes) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac")) {
            return null;
        }
        File inTmp = null;
        File outTmp = null;
        try {
            inTmp = File.createTempFile("luminaread_sips_in_", ".webp");
            outTmp = File.createTempFile("luminaread_sips_out_", ".png");
            Files.write(inTmp.toPath(), bytes);

            ProcessBuilder pb = new ProcessBuilder(
                    "sips", "-s", "format", "png", inTmp.getAbsolutePath(), "--out", outTmp.getAbsolutePath()
            );
            Process p = pb.start();
            boolean ok = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (ok && outTmp.exists() && outTmp.length() > 0) {
                return ImageIO.read(outTmp);
            }
        } catch (Throwable ignored) {
        } finally {
            if (inTmp != null) inTmp.delete();
            if (outTmp != null) outTmp.delete();
        }
        return null;
    }

    public static BufferedImage toBufferedImage(Image img) {
        if (img == null) return null;
        if (img instanceof BufferedImage) return (BufferedImage) img;

        int w = img.getWidth(null);
        int h = img.getHeight(null);
        if (w <= 0 || h <= 0) {
            java.awt.MediaTracker tracker = new java.awt.MediaTracker(new java.awt.Container());
            tracker.addImage(img, 0);
            try {
                tracker.waitForID(0, 3000);
            } catch (InterruptedException ignored) {
            }
            w = img.getWidth(null);
            h = img.getHeight(null);
        }
        if (w <= 0 || h <= 0) {
            return null;
        }

        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = bi.createGraphics();
        g2.drawImage(img, 0, 0, null);
        g2.dispose();
        return bi;
    }

    public static String replaceHost(String urlStr, String newBase) {
        if (urlStr == null || newBase == null) return null;
        try {
            URL u = URI.create(urlStr).toURL();
            String pathAndQuery = u.getFile();
            String cleanBase = newBase.replaceAll("/+$", "");
            return cleanBase + (pathAndQuery.startsWith("/") ? "" : "/") + pathAndQuery;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isMangaLibUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("cdnrulib") || lower.contains("cdnlibs") || lower.contains("imglib")
                || lower.contains("hentaicdn") || lower.contains("mangalib");
    }

    public static String hashUrl(String url) {
        if (url == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}

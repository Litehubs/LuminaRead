package com.luminaread.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** The remote image loader never requests a local or special-purpose destination. */
final class ImageUrlPolicy {
    @FunctionalInterface
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }

    private final Resolver resolver;
    private final boolean testLoopback;

    ImageUrlPolicy(Resolver resolver, boolean testLoopback) {
        this.resolver = resolver;
        this.testLoopback = testLoopback;
    }

    InetAddress[] check(URI address) throws IOException {
        String host = address.getHost();
        String scheme = address.getScheme();
        int port = address.getPort();
        if (scheme == null || host == null || address.getRawUserInfo() != null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw blocked();
        }
        if (!testLoopback && port != -1 && port != ("https".equalsIgnoreCase(scheme) ? 443 : 80)) throw blocked();
        String name = host.toLowerCase(Locale.ROOT);
        if (!testLoopback && (name.equals("localhost") || name.endsWith(".localhost")
                || name.endsWith(".local") || name.endsWith(".internal"))) throw blocked();
        InetAddress[] addresses = resolver.resolve(host);
        if (addresses.length == 0) throw blocked();
        for (InetAddress ip : addresses) {
            if (testLoopback && ip.isLoopbackAddress()) continue;
            if (!isPublic(ip)) throw blocked();
        }
        return addresses;
    }

    static URI redirect(URI from, String location) throws IOException {
        if (location == null || location.isBlank()) throw blocked();
        URI next = from.resolve(location);
        if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
            throw blocked();
        }
        return next;
    }

    private static boolean isPublic(InetAddress ip) {
        if (ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                || ip.isSiteLocalAddress() || ip.isMulticastAddress()) return false;
        byte[] bytes = ip.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        int third = bytes[2] & 255;
        if (bytes.length == 4) {
            return first != 0 && first < 224 && (first != 100 || second < 64 || second > 127)
                    && (first != 192 || second != 168 && (second != 0 || third != 0 && third != 2))
                    && (first != 198 || second != 18 && second != 19 && (second != 51 || third != 100))
                    && (first != 203 || second != 0 || third != 113);
        }
        // Admit only global IPv6 unicast and reject transition and documentation prefixes.
        return (first & 0xe0) == 0x20
                && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01 && (third < 2 || third == 0x0d && (bytes[3] & 255) == 0xb8));
    }

    private static IOException blocked() {
        return new IOException("Адрес удалённого изображения запрещён");
    }
}

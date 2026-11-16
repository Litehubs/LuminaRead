package com.luminaread.service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** One short-lived authorization attempt, bound to its PKCE verifier. */
public final class YandexPkce {
    private final String clientId;
    private final String verifier;
    private final String challenge;

    private YandexPkce(String clientId, String verifier, String challenge) {
        this.clientId = clientId;
        this.verifier = verifier;
        this.challenge = challenge;
    }

    public static YandexPkce create(String clientId) {
        if (clientId == null || clientId.isBlank()) throw new IllegalArgumentException("Не задан идентификатор приложения Яндекса.");
        try {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new YandexPkce(clientId, verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 недоступен", ex);
        }
    }

    public String verifier() { return verifier; }

    public URI authorizationUri(URI redirect, String state) {
        if (state == null || state.isBlank()) throw new IllegalArgumentException("Требуется состояние OAuth.");
        return URI.create("https://oauth.yandex.ru/authorize?response_type=code&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirect.toString())
                + "&code_challenge=" + encode(challenge) + "&code_challenge_method=S256"
                + "&state=" + encode(state));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}

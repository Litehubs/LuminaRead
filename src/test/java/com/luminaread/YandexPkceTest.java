package com.luminaread;

import com.luminaread.service.YandexPkce;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class YandexPkceTest {
    @Test void authorizationUsesUniqueVerifierAndMatchingChallenge() throws Exception {
        YandexPkce first = YandexPkce.create("client+id");
        YandexPkce second = YandexPkce.create("client+id");
        assertNotEquals(first.verifier(), second.verifier());
        assertTrue(first.verifier().length() >= 43 && first.verifier().length() <= 128);
        URI uri = first.authorizationUri();
        assertEquals("oauth.yandex.ru", uri.getHost());
        Map<String, String> query = java.util.Arrays.stream(uri.getRawQuery().split("&"))
            .map(part -> part.split("=", 2)).collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
        assertEquals("code", query.get("response_type"));
        assertEquals("client+id", query.get("client_id"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(first.verifier().getBytes(StandardCharsets.US_ASCII))), query.get("code_challenge"));
    }
}

package com.luminaread;

import com.luminaread.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class JsonValidationTest {
    @Test void rejectsMalformedJsonAndNonAsciiUnicodeEscapes() {
        String[] invalid = {"trash", "true false", "[1 2]", "{\"a\" 1}", "[1,]", "\"unfinished", "01",
                "\"\\q\"", "\"\\uＦＦＦＦ\"", ""};
        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(value), value);
        }
    }

    @Test void acceptsValidJsonWithUnicodeEscapes() {
        Map<?, ?> result = assertInstanceOf(Map.class,
                Json.parse("{\"ok\":true,\"items\":[null, -2, 1.5, \"text\\n\\u00Af\"]}"));
        assertEquals(Boolean.TRUE, result.get("ok"));
        List<?> items = assertInstanceOf(List.class, result.get("items"));
        assertEquals(4, items.size());
        assertEquals("text\n¯", items.get(3));
    }

    @Test void rejectsDeeplyNestedArraysAndObjectsWithoutOverflowingTheStack() {
        assertNotNull(Json.parse("[".repeat(128) + "0" + "]".repeat(128)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(10_000) + "0" + "]".repeat(10_000)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":".repeat(10_000) + "0" + "}".repeat(10_000)));
    }
}

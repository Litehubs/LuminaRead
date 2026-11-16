package com.luminaread.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DictionaryServiceTest {
    @Test void decodesJsonAndReadsDefinitionFromItsActualField() {
        var calls = new AtomicInteger();
        var service = new DictionaryService(request -> calls.getAndIncrement() == 0
                ? new DictionaryService.Response(200, "{\"responseStatus\":200,\"responseData\":{\"translatedText\":\"строка\\n\\\"текст\\\"\"}}")
                : new DictionaryService.Response(200, "[{\"meanings\":[{\"definitions\":[{\"definition\":\"a book\\nentry\"}]}]}]"));
        String result = service.lookupAndTranslate("book");
        assertTrue(result.contains("строка\n\"текст\""));
        assertTrue(result.contains("a book\nentry"));
        assertEquals(2, calls.get());
    }

    @Test void reportsHttpMalformedAndServiceErrors() {
        for (DictionaryService.Response response : new DictionaryService.Response[] {
                new DictionaryService.Response(503, "{}"),
                new DictionaryService.Response(200, "invalid json"),
                new DictionaryService.Response(200, "{\"responseStatus\":429,\"responseData\":{\"translatedText\":\"quota error\"}}") }) {
            String result = new DictionaryService(request -> response).lookupAndTranslate("привет");
            assertTrue(result.contains("Онлайн-перевод недоступен"));
            assertFalse(result.contains("quota error"));
        }
    }

    @Test void interruptionStopsAdditionalRequestsAndRestoresFlag() {
        var calls = new AtomicInteger();
        var service = new DictionaryService(request -> { calls.incrementAndGet(); throw new InterruptedException(); });
        try {
            assertTrue(service.lookupAndTranslate("book").contains("отменён"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, calls.get());
        } finally {
            Thread.interrupted();
        }
    }
}

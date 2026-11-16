package com.luminaread.service;

import com.luminaread.service.catalog.OpdsFeedParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class OpdsXmlSafetyTest {
    @Test void externalEntityCannotReadLocalFile(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("private.txt");
        Files.writeString(secret, "private-marker-must-not-appear");
        String xml = "<?xml version='1.0'?><!DOCTYPE feed [<!ENTITY leak SYSTEM '" + secret.toUri()
                + "'>]><feed><entry><title>&leak;</title><link rel='http://opds-spec.org/acquisition' "
                + "type='application/epub+zip' href='book.epub'/></entry></feed>";
        var books = new OpdsFeedParser().parse(xml, "https://example.org/", "test");
        assertTrue(books.stream().noneMatch(book -> book.getTitle().contains("private-marker-must-not-appear")));
    }
}

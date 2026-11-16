package com.luminaread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import static org.junit.jupiter.api.Assertions.*;

final class StarterBookTest {
    @Test void starterFb2IsWellFormed(@TempDir Path home) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(LuminaReadApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(java, "-Djava.awt.headless=true", "-Duser.home=" + home,
                "-cp", classes, "com.luminaread.LuminaReadApp")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Startup did not finish");
        Path book = home.resolve("LuminaRead_Books/Белые_ночи_Достоевский_Демо.fb2");
        assertTrue(Files.exists(book), "Starter book was not created");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.newDocumentBuilder().parse(book.toFile());
    }
}

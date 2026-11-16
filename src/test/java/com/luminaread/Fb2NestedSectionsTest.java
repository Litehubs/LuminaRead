package com.luminaread;

import com.luminaread.parser.Fb2Parser;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Fb2NestedSectionsTest {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("nested-sections-", ".fb2");
        try {
            Files.writeString(file, """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook><body><section><title><p>Part one</p></title>
                <p>Beginning of the outer section.</p>
                <section><title><p>Inner chapter</p></title><p>Text inside the nested section.</p></section>
                <p>Text following the nested section.</p></section>
                <section/><![CDATA[<section>]]>
                <section><title><p>Part two</p></title><p>Text of the next section.</p></section></body></FictionBook>
                """);
            var content = new Fb2Parser().parse(file.toFile());
            String text = content.getChapters().stream().map(chapter -> chapter.getPlainText()).reduce("", (a,b) -> a + " " + b);
            for (String phrase : new String[]{"Beginning of the outer section", "Text inside the nested section", "Text following the nested section", "Text of the next section"})
                if (!text.contains(phrase)) throw new AssertionError("Lost book text: " + phrase);
            System.out.println("PASS nested FB2 sections");
        } finally { Files.deleteIfExists(file); }
    }
}

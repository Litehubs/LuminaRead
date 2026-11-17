package com.luminaread.parser;

import org.junit.jupiter.api.Test;
import com.luminaread.model.BookContent;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PdfMacExtractionTest {
    @Test @EnabledOnOs(OS.MAC)
    void pdfKitExtractsTextWithoutSystemDiagnostics(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("sample.pdf");
        Files.write(pdf, samplePdf());
        BookContent content = new PdfParser().parse(pdf.toFile());
        assertEquals(2, content.getChapters().size());
        var first = content.getChapters().get(0);
        assertTrue(first.getPlainText().contains("===LUMINA_PAGE_410==="), first.getPlainText());
        assertTrue(PaginationEngine.paginateChapter(first, 600, false).stream()
                .anyMatch(page -> page.contains("page_1.png")));
        assertTrue(content.getChapters().get(1).getHtmlContent().contains("page_2.png"));
        String text = content.getChapters().stream().map(ch -> ch.getPlainText()).reduce("", (a, b) -> a + b);
        assertTrue(text.contains("LuminaRead PDF test content."), text);
        assertFalse(text.contains("Connection invalid"));
        assertFalse(text.contains("Connection Invalid"));
    }

    @Test @EnabledOnOs(OS.MAC)
    void damagedPdfShowsRecoverableInformation(@TempDir Path dir) throws Exception {
        Path pdf = Files.writeString(dir.resolve("truncated.pdf"), "%PDF-1.4\n1 0 obj");
        byte[] original = Files.readAllBytes(pdf);
        var content = new PdfParser().parse(pdf.toFile());
        assertEquals(1, content.getChapters().size());
        assertTrue(content.getChapters().get(0).getPlainText().contains("Не удалось извлечь текст"));
        assertArrayEquals(original, Files.readAllBytes(pdf));
    }

    private static byte[] samplePdf() throws Exception {
        String stream = "BT /F1 24 Tf 72 720 Td (LuminaRead PDF test content.) Tj "
                + "0 -32 Td (===LUMINA_PAGE_410===) Tj ET";
        String blank = "q 1 0 0 rg 72 72 30 30 re f Q";
        String[] objects = {"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 7 0 R >>",
                "<< /Length " + blank.length() + " >>\nstream\n" + blank + "\nendstream"};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(out.size()); write(out, (i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n");
        }
        int xref = out.size();
        write(out, "xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) write(out, String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        write(out, "trailer\n<< /Size " + (objects.length + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }
    private static void write(ByteArrayOutputStream out, String value) throws Exception { out.write(value.getBytes(StandardCharsets.US_ASCII)); }
}

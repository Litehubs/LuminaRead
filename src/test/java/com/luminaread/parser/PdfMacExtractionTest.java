package com.luminaread.parser;

import org.junit.jupiter.api.Test;
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
        var content = new PdfParser().parse(pdf.toFile());
        String text = content.getChapters().stream().map(ch -> ch.getPlainText()).reduce("", (a, b) -> a + b);
        assertTrue(text.contains("LuminaRead PDF test content."), text);
        assertFalse(text.contains("Connection invalid"));
        assertFalse(text.contains("Connection Invalid"));
    }

    private static byte[] samplePdf() throws Exception {
        String stream = "BT /F1 24 Tf 72 720 Td (LuminaRead PDF test content.) Tj ET";
        String[] objects = {"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream"};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(out.size()); write(out, (i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n");
        }
        int xref = out.size();
        write(out, "xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) write(out, String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        write(out, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }
    private static void write(ByteArrayOutputStream out, String value) throws Exception { out.write(value.getBytes(StandardCharsets.US_ASCII)); }
}

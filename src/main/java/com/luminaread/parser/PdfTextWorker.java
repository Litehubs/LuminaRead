package com.luminaread.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Runs PDF text extraction in a separate JVM with a fixed heap and output budget. */
final class PdfTextWorker {
    private PdfTextWorker() {}

    public static void main(String[] args) throws IOException {
        try (var document = Loader.loadPDF(new File(args[0]))) {
            if (document.getNumberOfPages() > PdfParser.MAX_PAGES
                    || !document.getCurrentAccessPermission().canExtractContent()) {
                throw new IOException("Не удалось извлечь текст PDF");
            }
            // A PrintStream would swallow overflow and break the worker's failure status.
            var output = new FilterOutputStream(new FileOutputStream(FileDescriptor.out)) {
                private long remaining = PdfParser.MAX_EXTRACTED_TEXT_BYTES;

                @Override public void write(int value) throws IOException {
                    reserve(1);
                    out.write(value);
                }

                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    reserve(length);
                    out.write(bytes, offset, length);
                }

                private void reserve(int length) throws IOException {
                    if (length > remaining) throw new IOException("Текст PDF превышает допустимый размер");
                    remaining -= length;
                }
            };
            try (var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                var stripper = new PDFTextStripper();
                stripper.setPageEnd("\f");
                stripper.writeText(document, writer);
            }
        }
    }
}

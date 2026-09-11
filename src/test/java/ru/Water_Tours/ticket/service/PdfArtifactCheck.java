package ru.Water_Tours.ticket.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Inspects a PDF produced by the running local backend: text, prices and QR decode. Run manually
 * with the file path as the argument; not a unit test, so it stays out of the automated suite.
 */
public final class PdfArtifactCheck {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            byte[] bytes = Files.readAllBytes(Path.of(arg));
            try (PDDocument doc = PDDocument.load(bytes)) {
                String text = new PDFTextStripper().getText(doc).replace(' ', ' ');
                System.out.println("== " + arg + " (" + doc.getNumberOfPages() + " pages, " + bytes.length + " bytes)");
                for (String line : text.split("\\R")) {
                    if (!line.isBlank()) System.out.println("   | " + line.strip());
                }
                for (int page = 0; page < doc.getNumberOfPages(); page++) {
                    var rendered = new PDFRenderer(doc).renderImageWithDPI(page, 150);
                    var bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(rendered)));
                    System.out.println("   QR page " + (page + 1) + ": " + new MultiFormatReader().decode(bitmap).getText());
                }
            }
        }
    }
}

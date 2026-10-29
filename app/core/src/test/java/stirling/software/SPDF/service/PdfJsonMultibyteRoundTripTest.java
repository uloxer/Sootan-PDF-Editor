package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.SPDF.model.json.PdfJsonDocument;
import stirling.software.pdf.json.LocalPdfJson;

import tools.jackson.databind.json.JsonMapper;

class PdfJsonMultibyteRoundTripTest {
    @TempDir Path root;

    @Test
    void preservesCidBytesForUnchangedAndEditedUnicodeText() throws Exception {
        Path fonts = Files.createDirectory(root.resolve("fonts"));
        try (var font = getClass().getResourceAsStream("/static/fonts/NotoSans-Regular.ttf")) {
            Files.copy(font, fonts.resolve("NotoSans-Regular.ttf"));
        }
        Path source = root.resolve("source.pdf"),
                json = root.resolve("source.json"),
                output = root.resolve("output.pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            var font = PDType0Font.load(document, fonts.resolve("NotoSans-Regular.ttf").toFile());
            try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, 16);
                stream.newLineAtOffset(72, 700);
                stream.showText("Swona café Ω");
                stream.endText();
            }
            document.save(source.toFile());
        }
        var converter = new LocalPdfJson(root, fonts, "");
        assertTrue(source.toFile().setReadOnly());
        try {
            converter.export(source, json);
        } finally {
            assertTrue(source.toFile().setWritable(true));
        }
        converter.rebuild(json, output);
        try (var document = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(document).contains("Swona café Ω"));
        }
        var mapper = JsonMapper.builder().build();
        var model = mapper.readValue(Files.readAllBytes(json), PdfJsonDocument.class);
        model.getPages().getFirst().getTextElements().getFirst().setText("Cliff café Ω");
        Files.write(json, mapper.writeValueAsBytes(model));
        converter.rebuild(json, output);
        try (var document = Loader.loadPDF(output.toFile())) {
            assertTrue(new PDFTextStripper().getText(document).contains("Cliff café Ω"));
        }
    }
}

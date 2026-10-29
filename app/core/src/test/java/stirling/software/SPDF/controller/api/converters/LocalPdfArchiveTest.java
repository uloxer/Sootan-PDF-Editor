package stirling.software.SPDF.controller.api.converters;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import stirling.software.pdf.PdfArchive;
import stirling.software.pdf.PdfStandards;

class LocalPdfArchiveTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void validatesRequestedStandardAfterSaving(int part) throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.addRect(20, 20, 80, 90);
                content.fill();
            }
            byte[] output = PdfArchive.convertLocal(document, "pdfa-" + part);
            assertThat(
                            new PdfStandards()
                                    .validateRequested(new ByteArrayInputStream(output), part + "b")
                                    .isCompliant())
                    .isTrue();
            try (var reopened = org.apache.pdfbox.Loader.loadPDF(output)) {
                assertThat(reopened.getNumberOfPages()).isEqualTo(1);
                assertThat(reopened.getPage(0).getMediaBox().getWidth())
                        .isEqualTo(page.getMediaBox().getWidth());
            }
            if (part == 3)
                assertThat(
                                new PdfStandards()
                                        .validateRequested(new ByteArrayInputStream(output), "1b")
                                        .isCompliant())
                        .isFalse();
        }
    }

    @Test
    void refusesUnembeddedFontsAndUnavailableModes() throws Exception {
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(FontName.HELVETICA), 12);
                content.showText("Swona");
                content.endText();
            }
            assertThatThrownBy(() -> PdfArchive.convertLocal(document, "pdfa-2b"))
                    .isInstanceOf(PdfArchive.HelperRequired.class);
            assertThatThrownBy(() -> PdfArchive.convertLocal(document, "pdfa-2a"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}

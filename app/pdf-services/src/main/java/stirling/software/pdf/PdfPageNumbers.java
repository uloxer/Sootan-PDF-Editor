package stirling.software.pdf;

import java.awt.Color;
import java.io.IOException;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Existing page-number placement and typography shared by both runtimes. */
public final class PdfPageNumbers {
    private PdfPageNumbers() {}

    /** Selection is zero-based in caller-provided order; filename contains no extension. */
    public static void apply(
            PDDocument document,
            java.util.List<Integer> pagesToNumberList,
            String customMargin,
            int position,
            int pageNumber,
            String customText,
            int zeroPad,
            float fontSize,
            String fontType,
            String fontColor,
            String filename)
            throws IOException {
        String formatN;
        if (zeroPad > 0) {
            formatN = String.format("%%0%dd", Math.max(0, zeroPad));
        } else {
            formatN = "%d";
        }

        Color color = Color.BLACK;
        if (fontColor != null && !fontColor.trim().isEmpty()) {
            try {
                color = Color.decode(fontColor);
            } catch (NumberFormatException e) {
                color = Color.BLACK;
            }
        }
        float marginFactor =
                switch (customMargin == null ? "" : customMargin.toLowerCase(Locale.ROOT)) {
                    case "small" -> 0.02f;
                    case "large" -> 0.05f;
                    case "x-large" -> 0.075f;
                    case "medium" -> 0.035f;
                    default -> 0.035f;
                };

        if (customText == null || customText.isEmpty()) {
            customText = "{n}";
        }

        int pos = Math.max(1, Math.min(9, position));

        for (int i : pagesToNumberList) {
            PDPage page = document.getPage(i);
            PDRectangle pageSize = page.getMediaBox();

            String nFormatted = String.format(formatN, pageNumber);
            String text =
                    customText
                            .replace("{n}", nFormatted)
                            .replace("{total}", String.valueOf(document.getNumberOfPages()))
                            .replace("{filename}", filename);

            PDType1Font currentFont =
                    switch (fontType == null ? "" : fontType.toLowerCase(Locale.ROOT)) {
                        case "courier" -> new PDType1Font(Standard14Fonts.FontName.COURIER);
                        case "times" -> new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
                        default -> new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                    };

            float textWidth = currentFont.getStringWidth(text) / 1000f * fontSize;
            float ascent = currentFont.getFontDescriptor().getAscent() / 1000f * fontSize;
            float descent = currentFont.getFontDescriptor().getDescent() / 1000f * fontSize;

            int col = ((pos - 1) % 3) + 1; // 1 = left, 2 = center, 3 = right
            int row = ((pos - 1) / 3) + 1; // 1 = top, 2 = middle, 3 = bottom

            float leftX = pageSize.getLowerLeftX() + marginFactor * pageSize.getWidth();
            float midX = pageSize.getLowerLeftX() + pageSize.getWidth() / 2f;
            float rightX = pageSize.getUpperRightX() - marginFactor * pageSize.getWidth();

            float botY = pageSize.getLowerLeftY() + marginFactor * pageSize.getHeight();
            float midY = pageSize.getLowerLeftY() + pageSize.getHeight() / 2f;
            float topY = pageSize.getUpperRightY() - marginFactor * pageSize.getHeight();

            float x =
                    switch (col) {
                        case 1 -> leftX;
                        case 2 -> midX - textWidth / 2f;
                        default -> rightX - textWidth;
                    };

            float y =
                    switch (row) {
                        case 1 -> topY - ascent;
                        case 2 -> midY - (ascent + descent) / 2f;
                        default -> botY;
                    };

            try (PDPageContentStream contentStream =
                    new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                contentStream.beginText();
                contentStream.setFont(currentFont, fontSize);
                contentStream.setNonStrokingColor(color);
                contentStream.newLineAtOffset(x, y);
                contentStream.showText(text);
                contentStream.endText();
            }

            pageNumber++;
        }
    }
}

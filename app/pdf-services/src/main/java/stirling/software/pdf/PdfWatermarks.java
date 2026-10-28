package stirling.software.pdf;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

/** Retained text/image watermark placement with bundled fonts only. */
public final class PdfWatermarks {
    private PdfWatermarks() {}

    /** Caller owns decoded image and document; normal output remains text/vector PDF content. */
    public static void apply(
            PDDocument document,
            String watermarkType,
            String watermarkText,
            BufferedImage watermarkImage,
            String alphabet,
            float fontSize,
            float rotation,
            float opacity,
            int widthSpacer,
            int heightSpacer,
            String customColor)
            throws IOException {
        PDFont font =
                "text".equalsIgnoreCase(watermarkType)
                        ? loadWatermarkFont(document, alphabet)
                        : null;

        for (PDPage page : document.getPages()) {
            try (PDPageContentStream contentStream =
                    new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                PDExtendedGraphicsState graphicsState = new PDExtendedGraphicsState();
                graphicsState.setNonStrokingAlphaConstant(opacity);
                contentStream.setGraphicsStateParameters(graphicsState);

                if ("text".equalsIgnoreCase(watermarkType)) {
                    addTextWatermark(
                            contentStream,
                            watermarkText,
                            page,
                            rotation,
                            widthSpacer,
                            heightSpacer,
                            fontSize,
                            font,
                            customColor);
                } else if ("image".equalsIgnoreCase(watermarkType)) {
                    addImageWatermark(
                            contentStream,
                            watermarkImage,
                            document,
                            page,
                            rotation,
                            widthSpacer,
                            heightSpacer,
                            fontSize);
                }
            }
        }
    }

    private static PDFont loadWatermarkFont(PDDocument document, String alphabet)
            throws IOException {
        String resourceDir =
                switch (alphabet) {
                    case "arabic" -> "static/fonts/NotoSansArabic-Regular.ttf";
                    case "japanese" -> "static/fonts/NotoSansJP-Regular.ttf";
                    case "korean" -> "static/fonts/NotoSansKR-Regular.ttf";
                    case "chinese" -> "static/fonts/NotoSansSC-Regular.ttf";
                    case "thai" -> "static/fonts/NotoSansThai-Regular.ttf";
                    default -> "static/fonts/NotoSans-Regular.ttf";
                };

        String root = System.getenv("SOOTAN_WORKER_FONTS");
        try (InputStream is =
                root == null
                        ? PdfWatermarks.class.getResourceAsStream("/" + resourceDir)
                        : java.nio.file.Files.newInputStream(
                                java.nio.file.Path.of(root)
                                        .resolve(
                                                resourceDir.substring(
                                                        resourceDir.lastIndexOf('/') + 1)))) {
            if (is == null) throw new IOException("The bundled watermark font is unavailable.");
            return PDType0Font.load(document, is);
        }
    }

    private static void addTextWatermark(
            PDPageContentStream contentStream,
            String watermarkText,
            PDPage page,
            float rotation,
            int widthSpacer,
            int heightSpacer,
            float fontSize,
            PDFont font,
            String colorString)
            throws IOException {
        contentStream.setFont(font, fontSize);

        Color redactColor;
        try {
            if (!colorString.startsWith("#")) {
                colorString = "#" + colorString;
            }
            redactColor = Color.decode(colorString);
        } catch (NumberFormatException e) {

            redactColor = Color.LIGHT_GRAY;
        }
        contentStream.setNonStrokingColor(redactColor);

        String[] textLines = java.util.regex.Pattern.compile("\\\\n").split(watermarkText);
        float maxLineWidth = 0;

        for (int i = 0; i < textLines.length; ++i) {
            maxLineWidth = Math.max(maxLineWidth, font.getStringWidth(textLines[i]));
        }

        float watermarkWidth = widthSpacer + maxLineWidth * fontSize / 1000;
        float watermarkHeight = heightSpacer + fontSize * textLines.length;
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        float radians = (float) Math.toRadians(rotation);
        float newWatermarkWidth =
                (float)
                        (Math.abs(watermarkWidth * Math.cos(radians))
                                + Math.abs(watermarkHeight * Math.sin(radians)));
        float newWatermarkHeight =
                (float)
                        (Math.abs(watermarkWidth * Math.sin(radians))
                                + Math.abs(watermarkHeight * Math.cos(radians)));

        int watermarkRows = Math.min((int) (pageHeight / newWatermarkHeight + 1), 10_000);
        int watermarkCols = Math.min((int) (pageWidth / newWatermarkWidth + 1), 10_000);

        if ((long) (watermarkRows + 1) * (watermarkCols + 1) > 100000)
            throw new IOException("Watermark density exceeds the private processing limit.");
        for (int i = 0; i <= watermarkRows; i++) {
            for (int j = 0; j <= watermarkCols; j++) {
                contentStream.beginText();
                contentStream.setTextMatrix(
                        Matrix.getRotateInstance(
                                (float) Math.toRadians(rotation),
                                j * newWatermarkWidth,
                                i * newWatermarkHeight));

                for (int k = 0; k < textLines.length; ++k) {
                    contentStream.showText(textLines[k]);
                    contentStream.newLineAtOffset(0, -fontSize);
                }

                contentStream.endText();
            }
        }
    }

    private static void addImageWatermark(
            PDPageContentStream contentStream,
            BufferedImage image,
            PDDocument document,
            PDPage page,
            float rotation,
            int widthSpacer,
            int heightSpacer,
            float fontSize)
            throws IOException {

        float aspectRatio = (float) image.getWidth() / (float) image.getHeight();

        float desiredPhysicalHeight = fontSize;

        float desiredPhysicalWidth = desiredPhysicalHeight * aspectRatio;

        PDImageXObject xobject = LosslessFactory.createFromImage(document, image);

        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();
        int watermarkRows =
                Math.min(
                        (int)
                                ((pageHeight + heightSpacer)
                                        / (desiredPhysicalHeight + heightSpacer)),
                        10_000);
        int watermarkCols =
                Math.min(
                        (int) ((pageWidth + widthSpacer) / (desiredPhysicalWidth + widthSpacer)),
                        10_000);

        if ((long) watermarkRows * watermarkCols > 100000)
            throw new IOException("Watermark density exceeds the private processing limit.");
        for (int i = 0; i < watermarkRows; i++) {
            for (int j = 0; j < watermarkCols; j++) {
                float x = j * (desiredPhysicalWidth + widthSpacer);
                float y = i * (desiredPhysicalHeight + heightSpacer);

                contentStream.saveGraphicsState();

                contentStream.transform(
                        Matrix.getTranslateInstance(
                                x + desiredPhysicalWidth / 2, y + desiredPhysicalHeight / 2));
                contentStream.transform(Matrix.getRotateInstance(Math.toRadians(rotation), 0, 0));
                contentStream.transform(
                        Matrix.getTranslateInstance(
                                -desiredPhysicalWidth / 2, -desiredPhysicalHeight / 2));

                contentStream.drawImage(xobject, 0, 0, desiredPhysicalWidth, desiredPhysicalHeight);
                contentStream.restoreGraphicsState();
            }
        }
    }
}

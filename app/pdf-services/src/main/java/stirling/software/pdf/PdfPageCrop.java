package stirling.software.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.BitSet;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Existing PDFBox crop algorithms; clipping does not securely remove hidden data. */
public final class PdfPageCrop {
    private static final int DEFAULT_RENDER_DPI = 150;
    private static final int WHITE_THRESHOLD = 250;

    private PdfPageCrop() {}

    private static int[] detectContentBounds(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        if (width < 1 || height < 1) {
            return new int[] {0, 0, width - 1, height - 1};
        }

        int step = (width > 2000 || height > 2000) ? 2 : 1;

        int top = 0;
        boolean found = false;
        for (int y = 0; y < height && !found; y += step) {
            for (int x = 0; x < width; x += step) {
                if (!isWhite(image.getRGB(x, y), WHITE_THRESHOLD)) {
                    top = y;
                    found = true;
                    break;
                }
            }
        }

        int bottom = height - 1;
        found = false;
        for (int y = height - 1; y >= 0 && !found; y -= step) {
            for (int x = 0; x < width; x += step) {
                if (!isWhite(image.getRGB(x, y), WHITE_THRESHOLD)) {
                    bottom = y;
                    found = true;
                    break;
                }
            }
        }

        int left = 0;
        found = false;
        for (int x = 0; x < width && !found; x += step) {
            for (int y = top; y <= bottom; y += step) {
                if (!isWhite(image.getRGB(x, y), WHITE_THRESHOLD)) {
                    left = x;
                    found = true;
                    break;
                }
            }
        }

        int right = width - 1;
        found = false;
        for (int x = width - 1; x >= 0 && !found; x -= step) {
            for (int y = top; y <= bottom; y += step) {
                if (!isWhite(image.getRGB(x, y), WHITE_THRESHOLD)) {
                    right = x;
                    found = true;
                    break;
                }
            }
        }

        return new int[] {left, height - bottom - 1, right, height - top - 1};
    }

    private static boolean isWhite(int rgb, int threshold) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r >= threshold && g >= threshold && b >= threshold;
    }

    /**
     * Selected pages are zero-based; automatic detection renders only for measurement, not export.
     */
    public static void automatic(
            PDDocument sourceDocument,
            PDDocument newDocument,
            BitSet pagesToCrop,
            float x,
            float y,
            float width,
            float height)
            throws IOException {
        PDFRenderer renderer = new PDFRenderer(sourceDocument);
        renderer.setSubsamplingAllowed(true); // Enable subsampling to reduce memory usage
        LayerUtility layerUtility = new LayerUtility(newDocument);

        for (int i = 0; i < sourceDocument.getNumberOfPages(); i++) {
            if (!pagesToCrop.get(i)) {
                PDPage imported = newDocument.importPage(sourceDocument.getPage(i));
                imported.setResources(sourceDocument.getPage(i).getResources());
                continue;
            }

            PDPage sourcePage = sourceDocument.getPage(i);
            PDRectangle mediaBox = sourcePage.getMediaBox();

            var box = sourcePage.getCropBox();
            if ((double) box.getWidth()
                            * box.getHeight()
                            * DEFAULT_RENDER_DPI
                            * DEFAULT_RENDER_DPI
                            / (72d * 72d)
                    > 100_000_000d)
                throw new IOException("Auto-crop exceeds the render pixel limit.");
            BufferedImage image = renderer.renderImageWithDPI(i, DEFAULT_RENDER_DPI);
            int[] bounds = detectContentBounds(image);

            float scaleX = mediaBox.getWidth() / image.getWidth();
            float scaleY = mediaBox.getHeight() / image.getHeight();

            image.flush();
            CropBounds cropBounds = CropBounds.fromPixels(bounds, scaleX, scaleY);

            PDPage newPage = new PDPage(mediaBox);
            newDocument.addPage(newPage);
            try (PDPageContentStream contentStream =
                    new PDPageContentStream(
                            newDocument, newPage, AppendMode.OVERWRITE, true, true)) {
                PDFormXObject formXObject = layerUtility.importPageAsForm(sourceDocument, i);
                contentStream.saveGraphicsState();
                contentStream.addRect(
                        cropBounds.x, cropBounds.y, cropBounds.width, cropBounds.height);
                contentStream.clip();
                contentStream.drawForm(formXObject);
                contentStream.restoreGraphicsState();
            }

            newPage.setMediaBox(
                    new PDRectangle(
                            cropBounds.x, cropBounds.y, cropBounds.width, cropBounds.height));
        }
    }

    /**
     * Selected pages are zero-based; automatic detection renders only for measurement, not export.
     */
    public static void manual(
            PDDocument sourceDocument,
            PDDocument newDocument,
            BitSet pagesToCrop,
            float x,
            float y,
            float width,
            float height)
            throws IOException {
        int totalPages = sourceDocument.getNumberOfPages();
        LayerUtility layerUtility = new LayerUtility(newDocument);

        for (int i = 0; i < totalPages; i++) {
            if (!pagesToCrop.get(i)) {
                PDPage imported = newDocument.importPage(sourceDocument.getPage(i));
                imported.setResources(sourceDocument.getPage(i).getResources());
                continue;
            }

            PDPage sourcePage = sourceDocument.getPage(i);

            PDPage newPage = new PDPage(sourcePage.getMediaBox());
            newDocument.addPage(newPage);
            try (PDPageContentStream contentStream =
                    new PDPageContentStream(
                            newDocument, newPage, AppendMode.OVERWRITE, true, true)) {
                PDFormXObject formXObject = layerUtility.importPageAsForm(sourceDocument, i);

                contentStream.saveGraphicsState();

                contentStream.addRect(x, y, width, height);
                contentStream.clip();

                contentStream.drawForm(formXObject);

                contentStream.restoreGraphicsState();
            }

            newPage.setMediaBox(new PDRectangle(x, y, width, height));
        }
    }

    private record CropBounds(float x, float y, float width, float height) {

        static CropBounds fromPixels(int[] pixelBounds, float scaleX, float scaleY) {
            if (pixelBounds.length != 4) {
                throw new IllegalArgumentException(
                        "pixelBounds array must contain exactly 4 elements: [x1, y1, x2, y2]");
            }
            float x = pixelBounds[0] * scaleX;
            float y = pixelBounds[1] * scaleY;
            float width = (pixelBounds[2] - pixelBounds[0]) * scaleX;
            float height = (pixelBounds[3] - pixelBounds[1]) * scaleY;
            return new CropBounds(x, y, width, height);
        }
    }
}

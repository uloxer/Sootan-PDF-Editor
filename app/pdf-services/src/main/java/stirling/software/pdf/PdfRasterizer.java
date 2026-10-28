package stirling.software.pdf;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.*;

/** Explicit lossless300DPI PDF-to-image mode, matching the watermark tool's existing opt-in. */
public final class PdfRasterizer {
    private PdfRasterizer() {}

    public static void append(PDDocument source, PDDocument target) throws IOException {
        var renderer = new PDFRenderer(source);
        renderer.setSubsamplingAllowed(true);
        for (int i = 0; i < source.getNumberOfPages(); i++) {
            var original = source.getPage(i);
            var crop = original.getCropBox();
            if ((double) crop.getWidth() * crop.getHeight() * 300 * 300 / (72d * 72d)
                    > 100_000_000d)
                throw new IOException("The raster page exceeds the pixel limit.");
            var image = renderer.renderImageWithDPI(i, 300, ImageType.RGB);
            try {
                float width = original.getMediaBox().getWidth(),
                        height = original.getMediaBox().getHeight();
                var page = new PDPage(new PDRectangle(width, height));
                target.addPage(page);
                try (var content =
                        new PDPageContentStream(
                                target, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    content.drawImage(
                            LosslessFactory.createFromImage(target, image), 0, 0, width, height);
                }
            } finally {
                image.flush();
            }
        }
    }
}

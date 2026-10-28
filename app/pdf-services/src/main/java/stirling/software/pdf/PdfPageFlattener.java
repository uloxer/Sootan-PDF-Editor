package stirling.software.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;

/**
 * Full-page flattening is an explicit destructive raster mode, separate from forms-only flattening.
 */
public final class PdfPageFlattener {
    private PdfPageFlattener() {}

    /** Appends a rendered page using the source MediaBox, matching the existing flatten tool. */
    public static void appendRenderedPage(PDDocument target, PDPage source, BufferedImage image)
            throws IOException {
        PDPage page = new PDPage();
        page.setMediaBox(source.getMediaBox());
        target.addPage(page);
        try (PDPageContentStream content =
                new PDPageContentStream(
                        target, page, PDPageContentStream.AppendMode.OVERWRITE, true, true)) {
            content.drawImage(
                    JPEGFactory.createFromImage(target, image),
                    0,
                    0,
                    page.getMediaBox().getWidth(),
                    page.getMediaBox().getHeight());
        }
    }
}

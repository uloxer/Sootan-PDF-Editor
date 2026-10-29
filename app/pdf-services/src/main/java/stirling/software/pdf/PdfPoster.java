package stirling.software.pdf;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

import lombok.extern.slf4j.Slf4j;

/** Existing poster tile ordering and paper sizing; content remains vector. */
@Slf4j
public final class PdfPoster {
    public record Options(String pageSize, int xFactor, int yFactor, boolean rightToLeft) {}

    private static IllegalArgumentException invalid(String code, String pattern, Object... args) {
        return new IllegalArgumentException(java.text.MessageFormat.format(pattern, args));
    }

    public static void apply(PDDocument sourceDocument, PDDocument outputDocument, Options request)
            throws IOException {
        if (request.xFactor() < 1
                || request.xFactor() > 10
                || request.yFactor() < 1
                || request.yFactor() > 10
                || (long) sourceDocument.getNumberOfPages() * request.xFactor() * request.yFactor()
                        > 1000)
            throw new IllegalArgumentException(
                    "Poster output exceeds its 1–10 grid/1000-page budget.");
        // Get target page size
        PDRectangle targetPageSize = pageSize(request.pageSize());
        log.debug(
                "Target page size: {} ({}x{})",
                request.pageSize(),
                targetPageSize.getWidth(),
                targetPageSize.getHeight());

        // Create LayerUtility for importing pages as forms
        LayerUtility layerUtility = new LayerUtility(outputDocument);

        int totalPages = sourceDocument.getNumberOfPages();
        int xFactor = request.xFactor();
        int yFactor = request.yFactor();
        boolean rightToLeft = request.rightToLeft();

        log.debug(
                "Processing {} pages with grid {}x{}, RTL={}",
                totalPages,
                xFactor,
                yFactor,
                rightToLeft);

        // Process each page
        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            PDPage sourcePage = sourceDocument.getPage(pageIndex);

            PDRectangle visible = PdfLayouts.displayedSize(sourcePage);
            float sourceWidth = visible.getWidth(), sourceHeight = visible.getHeight();
            PDFormXObject form = PdfLayouts.displayForm(sourceDocument, pageIndex, layerUtility);
            // Calculate cell dimensions in source page coordinates
            float cellWidth = sourceWidth / xFactor;
            float cellHeight = sourceHeight / yFactor;

            // Create grid cells (rows × columns)
            for (int row = 0; row < yFactor; row++) {
                for (int col = 0; col < xFactor; col++) {
                    // Apply RTL ordering for columns if enabled
                    int actualCol = rightToLeft ? (xFactor - 1 - col) : col;

                    // Calculate crop rectangle in source coordinates
                    // PDF coordinates start at bottom-left
                    float cropX = actualCol * cellWidth;
                    // For Y: invert so row 0 shows TOP (following
                    // SplitPdfBySectionsController
                    // pattern)
                    float cropY = (yFactor - 1 - row) * cellHeight;

                    // Create new output page with target size
                    PDPage outputPage = new PDPage(targetPageSize);
                    outputDocument.addPage(outputPage);

                    try (PDPageContentStream contentStream =
                            new PDPageContentStream(
                                    outputDocument,
                                    outputPage,
                                    PDPageContentStream.AppendMode.APPEND,
                                    true,
                                    true)) {

                        // Calculate uniform scale to fit cell into target page
                        // Scale UP if cell is smaller than target, scale DOWN if larger
                        float scaleX = targetPageSize.getWidth() / cellWidth;
                        float scaleY = targetPageSize.getHeight() / cellHeight;
                        float scale = Math.min(scaleX, scaleY);

                        // Center the scaled content on the target page
                        float scaledCellWidth = cellWidth * scale;
                        float scaledCellHeight = cellHeight * scale;
                        float offsetX = (targetPageSize.getWidth() - scaledCellWidth) / 2;
                        float offsetY = (targetPageSize.getHeight() - scaledCellHeight) / 2;

                        // Apply transformations
                        contentStream.saveGraphicsState();

                        // Translate to center position
                        contentStream.transform(Matrix.getTranslateInstance(offsetX, offsetY));

                        // Scale uniformly
                        contentStream.transform(Matrix.getScaleInstance(scale, scale));

                        contentStream.addRect(0, 0, cellWidth, cellHeight);
                        contentStream.clip();
                        // Translate to show only the desired grid cell
                        // IMPORTANT: The PDFormXObject's BBox already matches the CropBox
                        // (including its offset), so we only need to translate by
                        // cropX/cropY
                        // relative to the CropBox origin, NOT the MediaBox origin
                        contentStream.transform(Matrix.getTranslateInstance(-cropX, -cropY));

                        // Draw the form
                        contentStream.drawForm(form);

                        contentStream.restoreGraphicsState();
                    }

                    log.trace(
                            "Created output page for grid cell [{},{}] of page {}: cropX={}, cropY={}, translate=({}, {})",
                            row,
                            actualCol,
                            pageIndex,
                            cropX,
                            cropY,
                            -cropX,
                            -cropY);
                }
            }
        }
    }

    public static PDRectangle pageSize(String pageSize) {
        Map<String, PDRectangle> sizeMap = new HashMap<>();
        sizeMap.put("A4", PDRectangle.A4);
        sizeMap.put("Letter", PDRectangle.LETTER);
        sizeMap.put("A3", PDRectangle.A3);
        sizeMap.put("A5", PDRectangle.A5);
        sizeMap.put("Legal", PDRectangle.LEGAL);
        sizeMap.put("Tabloid", new PDRectangle(792, 1224)); // 11x17 inches

        PDRectangle size = sizeMap.get(pageSize);
        if (size == null) {
            throw invalid(
                    "error.invalidPageSize",
                    "Invalid page size: {0}",
                    pageSize,
                    String.join(", ", sizeMap.keySet()));
        }
        return size;
    }
}

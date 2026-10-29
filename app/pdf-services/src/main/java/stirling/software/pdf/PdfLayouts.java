package stirling.software.pdf;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

/** Vector page-form layouts shared by local and legacy adapters; caller owns input and output. */
public final class PdfLayouts {
    private static int padToMultipleOf4(int n) {
        return (n + 3) / 4 * 4;
    }

    private static class Side {
        final int left, right;
        final boolean isBack;

        Side(int left, int right, boolean isBack) {
            this.left = left;
            this.right = right;
            this.isBack = isBack;
        }
    }

    private static List<Side> saddleStitchSides(
            int totalPagesOriginal,
            boolean doubleSided,
            String duplexPass,
            boolean flipOnShortEdge) {
        int N = padToMultipleOf4(totalPagesOriginal);
        List<Side> out = new ArrayList<>();
        int sheets = N / 4;

        for (int s = 0; s < sheets; s++) {
            int a = N - 1 - (s * 2); // left, front
            int b = (s * 2); // right, front
            int c = (s * 2) + 1; // left, back
            int d = N - 2 - (s * 2); // right, back

            // clamp to -1 (blank) if >= totalPagesOriginal
            a = (a < totalPagesOriginal) ? a : -1;
            b = (b < totalPagesOriginal) ? b : -1;
            c = (c < totalPagesOriginal) ? c : -1;
            d = (d < totalPagesOriginal) ? d : -1;

            // Handle duplex pass selection
            boolean includeFront = "BOTH".equals(duplexPass) || "FIRST".equals(duplexPass);
            boolean includeBack = "BOTH".equals(duplexPass) || "SECOND".equals(duplexPass);

            if (includeFront) {
                out.add(new Side(a, b, false)); // front side
            }

            if (includeBack) {
                // For short-edge duplex, swap back-side left/right
                // Note: flipOnShortEdge is ignored in manual duplex mode since users physically
                // flip the stack
                if (doubleSided && flipOnShortEdge) {
                    out.add(new Side(d, c, true)); // swapped back side (automatic duplex only)
                } else {
                    out.add(new Side(c, d, true)); // normal back side
                }
            }
        }
        return out;
    }

    public static PDDocument booklet(
            PDDocument src,
            PDDocument dst,
            int totalPages,
            boolean addBorder,
            String spineLocation,
            boolean addGutter,
            float gutterSize,
            boolean doubleSided,
            String duplexPass,
            boolean flipOnShortEdge)
            throws IOException {

        // Derive paper size from source document's first page CropBox
        PDRectangle srcBox = src.getPage(0).getCropBox();
        PDRectangle portraitPaper = new PDRectangle(srcBox.getWidth(), srcBox.getHeight());
        // Force landscape for booklet (Acrobat booklet uses landscape paper to fold to portrait)
        PDRectangle pageSize = new PDRectangle(portraitPaper.getHeight(), portraitPaper.getWidth());

        // Validate and clamp gutter size
        if (gutterSize < 0) gutterSize = 0;
        if (gutterSize >= pageSize.getWidth() / 2f) gutterSize = pageSize.getWidth() / 2f - 1f;

        List<Side> sides = saddleStitchSides(totalPages, doubleSided, duplexPass, flipOnShortEdge);

        for (Side side : sides) {
            PDPage out = new PDPage(pageSize);
            dst.addPage(out);

            float cellW = pageSize.getWidth() / 2f;
            float cellH = pageSize.getHeight();

            // For RIGHT spine (RTL), swap left/right placements
            boolean rtl = "RIGHT".equalsIgnoreCase(spineLocation);
            int leftCol = rtl ? 1 : 0;
            int rightCol = rtl ? 0 : 1;

            // Apply gutter margins with centered gap option
            float g = addGutter ? gutterSize : 0f;
            float leftCellX = leftCol * cellW + (g / 2f);
            float rightCellX = rightCol * cellW - (g / 2f);
            float leftCellW = cellW - (g / 2f);
            float rightCellW = cellW - (g / 2f);

            // Create LayerUtility once per page for efficiency
            LayerUtility layerUtility = new LayerUtility(dst);

            try (PDPageContentStream cs =
                    new PDPageContentStream(
                            dst, out, PDPageContentStream.AppendMode.APPEND, true, true)) {

                if (addBorder) {
                    cs.setLineWidth(1.5f);
                    cs.setStrokingColor(Color.BLACK);
                }

                // draw left cell
                drawCell(
                        src,
                        dst,
                        cs,
                        layerUtility,
                        side.left,
                        leftCellX,
                        0f,
                        leftCellW,
                        cellH,
                        addBorder);
                // draw right cell
                drawCell(
                        src,
                        dst,
                        cs,
                        layerUtility,
                        side.right,
                        rightCellX,
                        0f,
                        rightCellW,
                        cellH,
                        addBorder);
            }
        }
        return dst;
    }

    private static void drawCell(
            PDDocument src,
            PDDocument dst,
            PDPageContentStream cs,
            LayerUtility layerUtility,
            int pageIndex,
            float cellX,
            float cellY,
            float cellW,
            float cellH,
            boolean addBorder)
            throws IOException {

        if (pageIndex < 0) {
            // Draw border for blank cell if needed
            if (addBorder) {
                cs.addRect(cellX, cellY, cellW, cellH);
                cs.stroke();
            }
            return;
        }

        PDPage srcPage = src.getPage(pageIndex);
        PDRectangle r = srcPage.getCropBox(); // Use CropBox instead of MediaBox
        int rot = (srcPage.getRotation() + 360) % 360;

        // Calculate scale factors, accounting for rotation
        float sx = cellW / r.getWidth();
        float sy = cellH / r.getHeight();
        float s = Math.min(sx, sy);

        // If rotated 90/270 degrees, swap dimensions for fitting
        if (rot == 90 || rot == 270) {
            sx = cellW / r.getHeight();
            sy = cellH / r.getWidth();
            s = Math.min(sx, sy);
        }

        float drawnW = (rot == 90 || rot == 270) ? r.getHeight() * s : r.getWidth() * s;
        float drawnH = (rot == 90 || rot == 270) ? r.getWidth() * s : r.getHeight() * s;

        // Center in cell, accounting for CropBox offset
        float tx = cellX + (cellW - drawnW) / 2f;
        float ty = cellY + (cellH - drawnH) / 2f;

        cs.saveGraphicsState();
        cs.transform(Matrix.getTranslateInstance(tx, ty));
        cs.transform(Matrix.getScaleInstance(s, s));

        PDFormXObject form = displayForm(src, pageIndex, layerUtility);
        cs.drawForm(form);

        cs.restoreGraphicsState();

        // Draw border on top of form to ensure visibility
        if (addBorder) {
            cs.addRect(cellX, cellY, cellW, cellH);
            cs.stroke();
        }
    }

    /** Stacks the original vector page forms vertically in caller-owned output. */
    public static void singlePage(PDDocument sourceDocument, PDDocument newDocument)
            throws IOException {
        // Calculate total height and max width
        float totalHeight = 0;
        float maxWidth = 0;
        for (PDPage page : sourceDocument.getPages()) {
            PDRectangle pageSize = displayedSize(page);
            totalHeight += pageSize.getHeight();
            maxWidth = Math.max(maxWidth, pageSize.getWidth());
        }

        PDPage newPage = new PDPage(new PDRectangle(maxWidth, totalHeight));
        newDocument.addPage(newPage);

        LayerUtility layerUtility = new LayerUtility(newDocument);
        float yOffset = totalHeight;

        // For each page, copy its content to the new page at the correct offset
        try {
            layerUtility.wrapInSaveRestore(newPage);
        } catch (NullPointerException e) {
        }

        int pageIndex = 0;
        for (PDPage page : sourceDocument.getPages()) {
            PDFormXObject form = displayForm(sourceDocument, pageIndex, layerUtility);
            if (form != null) {
                AffineTransform af =
                        AffineTransform.getTranslateInstance(
                                0, yOffset - displayedSize(page).getHeight());
                String defaultLayerName = "Layer" + pageIndex;
                layerUtility.appendFormAsLayer(newPage, form, af, defaultLayerName);
            }
            yOffset -= displayedSize(page).getHeight();
            pageIndex++;
        }
    }

    static PDRectangle displayedSize(PDPage page) {
        PDRectangle box = page.getCropBox();
        int rotation = Math.floorMod(page.getRotation(), 360);
        return rotation == 90 || rotation == 270
                ? new PDRectangle(box.getHeight(), box.getWidth())
                : new PDRectangle(box.getWidth(), box.getHeight());
    }

    static PDFormXObject displayForm(PDDocument source, int index, LayerUtility utility)
            throws IOException {
        PDPage page = source.getPage(index);
        PDRectangle box = page.getCropBox();
        float x = box.getLowerLeftX(),
                y = box.getLowerLeftY(),
                w = box.getWidth(),
                h = box.getHeight();
        AffineTransform transform =
                switch (Math.floorMod(page.getRotation(), 360)) {
                    case 0 -> new AffineTransform(1, 0, 0, 1, -x, -y);
                    case 90 -> new AffineTransform(0, -1, 1, 0, -y, w + x);
                    case 180 -> new AffineTransform(-1, 0, 0, -1, w + x, h + y);
                    case 270 -> new AffineTransform(0, 1, -1, 0, h + y, -x);
                    default -> throw new IOException("Unsupported page rotation.");
                };
        PDFormXObject form = utility.importPageAsForm(source, index);
        form.setMatrix(transform);
        return form;
    }
}

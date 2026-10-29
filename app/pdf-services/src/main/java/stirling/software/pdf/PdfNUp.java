package stirling.software.pdf;

import java.awt.Color;
import java.io.IOException;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

import lombok.extern.slf4j.Slf4j;

import stirling.software.common.util.GeneralFormCopyUtils;

/** Existing N-up grid/content and editable-form placement; caller owns both documents. */
@Slf4j
public final class PdfNUp {
    @lombok.Data
    public static class Options {
        private String mode = null;
        private int pagesPerSheet = 2;
        private int rows = 0;
        private int cols = 0;
        private String orientation = null;
        private String arrangement = null;
        private String readingDirection = null;
        private int innerMargin = 0;
        private int topMargin = 0;
        private int bottomMargin = 0;
        private int leftMargin = 0;
        private int rightMargin = 0;
        private int borderWidth = 0;
        private Boolean addBorder = null;
    }

    private static IllegalArgumentException invalid(String code, String pattern, Object... args) {
        return new IllegalArgumentException(java.text.MessageFormat.format(pattern, args));
    }

    public static void apply(PDDocument sourceDocument, PDDocument newDocument, Options request)
            throws IOException {
        int MAX_PAGES = 100000;
        int MAX_COLS = 300;
        int MAX_ROWS = 300;

        String mode = request.getMode();
        if (mode == null || mode.trim().isEmpty()) {
            mode = "DEFAULT";
        }

        int rows;
        int cols;
        int pagesPerSheet;

        switch (mode) {
            case "DEFAULT":
                pagesPerSheet = request.getPagesPerSheet();
                if (pagesPerSheet != 2
                        && pagesPerSheet
                                != (int) Math.sqrt(pagesPerSheet) * Math.sqrt(pagesPerSheet)) {
                    throw invalid(
                            "error.invalidFormat",
                            "Invalid {0} format: {1}",
                            "pagesPerSheet",
                            "must be 2 or a perfect square");
                }

                cols = pagesPerSheet == 2 ? pagesPerSheet : (int) Math.sqrt(pagesPerSheet);
                rows = pagesPerSheet == 2 ? 1 : (int) Math.sqrt(pagesPerSheet);
                break;
            case "CUSTOM":
                rows = request.getRows();
                cols = request.getCols();
                if (rows <= 0 || cols <= 0) {
                    throw invalid(
                            "error.invalidFormat",
                            "Invalid {0} format: {1}",
                            "rows and cols",
                            "only strictly positive values are allowed");
                }
                pagesPerSheet = cols * rows;
                break;
            default:
                throw invalid(
                        "error.invalidFormat",
                        "Invalid {0} format: {1}",
                        "mode",
                        "only 'DEFAULT' and 'CUSTOM' are supported");
        }

        if (pagesPerSheet < 1 || pagesPerSheet > MAX_PAGES) {
            throw invalid(
                    "error.invalidArgument",
                    "Invalid {0} format: {1}",
                    "pagesPerSheet",
                    "must be less than " + MAX_PAGES);
        }
        if (cols > MAX_COLS) {
            throw invalid(
                    "error.invalidArgument",
                    "Invalid {0} format: {1}",
                    "cols",
                    "must be less than " + MAX_COLS);
        }
        if (rows > MAX_ROWS) {
            throw invalid(
                    "error.invalidArgument",
                    "Invalid {0} format: {1}",
                    "rows",
                    "must be less than " + MAX_ROWS);
        }

        String orientation = request.getOrientation();
        if (orientation == null || orientation.trim().isEmpty()) {
            orientation = "PORTRAIT";
        }
        if (!"PORTRAIT".equals(orientation) && !"LANDSCAPE".equals(orientation)) {
            throw invalid(
                    "error.invalidFormat",
                    "Invalid {0} format: {1}",
                    "orientation",
                    "only 'PORTRAIT' and 'LANDSCAPE' are supported");
        }

        String arrangement = request.getArrangement();
        if (arrangement == null || arrangement.trim().isEmpty()) {
            arrangement = "BY_ROWS";
        }
        if (!"BY_ROWS".equals(arrangement) && !"BY_COLUMNS".equals(arrangement)) {
            throw invalid(
                    "error.invalidFormat",
                    "Invalid {0} format: {1}",
                    "arrangement",
                    "only 'BY_ROWS' and 'BY_COLUMNS' are supported");
        }

        String readingDirection = request.getReadingDirection();
        if (readingDirection == null || readingDirection.trim().isEmpty()) {
            readingDirection = "LTR";
        }
        if (!"LTR".equals(readingDirection) && !"RTL".equals(readingDirection)) {
            throw invalid(
                    "error.invalidFormat",
                    "Invalid {0} format: {1}",
                    "readingDirection",
                    "only 'LTR' and 'RTL' are supported");
        }

        boolean addBorder = Boolean.TRUE.equals(request.getAddBorder());

        int topMargin = request.getTopMargin();
        int bottomMargin = request.getBottomMargin();
        int leftMargin = request.getLeftMargin();
        int rightMargin = request.getRightMargin();
        int innerMargin = request.getInnerMargin();

        if (topMargin < 0
                || bottomMargin < 0
                || leftMargin < 0
                || rightMargin < 0
                || innerMargin < 0) {
            throw invalid(
                    "error.invalidFormat",
                    "Invalid {0} format: {1}",
                    "Margins",
                    "only positive values are allowed");
        }

        int borderWidth = request.getBorderWidth() == 0 ? 1 : request.getBorderWidth();

        if (addBorder && borderWidth <= 0) {
            throw invalid(
                    "error.invalidFormat",
                    "Invalid {0} format: {1}",
                    "borderWidth",
                    "only strictly positive values are allowed when addBorder is true");
        }

        int totalPages = sourceDocument.getNumberOfPages();
        var placements = new java.util.ArrayList<GeneralFormCopyUtils.Placement>();
        LayerUtility layerUtility = new LayerUtility(newDocument);

        // Margin between page and content:
        float pageWidth =
                "PORTRAIT".equals(orientation)
                        ? PDRectangle.A4.getWidth()
                        : PDRectangle.A4.getHeight();
        float pageHeight =
                "PORTRAIT".equals(orientation)
                        ? PDRectangle.A4.getHeight()
                        : PDRectangle.A4.getWidth();

        // Calculate cell dimensions once (all output pages are A4) - declare outside try
        // blocks
        float cellWidth = (pageWidth - leftMargin - rightMargin) / cols;
        float cellHeight = (pageHeight - topMargin - bottomMargin) / rows;
        // Validate that outer margins and grid configuration yield positive cell size
        if (cellWidth <= 0 || cellHeight <= 0) {
            throw invalid(
                    "error.invalidFormat",
                    "Invalid {0} format: {1}",
                    "margin/layout configuration",
                    "Invalid margin or layout configuration: resulting cell size is non-positive. "
                            + "Please reduce outer margins or adjust rows/columns.");
        }

        float innerWidth = cellWidth - 2 * innerMargin;
        float innerHeight = cellHeight - 2 * innerMargin;
        // Validate that inner margin fits within each cell
        if (innerWidth <= 0 || innerHeight <= 0) {
            throw invalid(
                    "error.invalidFormat",
                    "Invalid {0} format: {1}",
                    "inner margin",
                    "Invalid inner margin: resulting inner content area is non-positive. "
                            + "Please reduce inner margin or adjust outer margins/layout.");
        }

        // Process pages in groups of pagesPerSheet, creating a new page and content stream
        // for each group
        for (int i = 0; i < totalPages; i += pagesPerSheet) {
            // Create a new output page for each group of pagesPerSheet
            // Create a new A4 landscape rectangle that we use when orientation is landscape
            PDRectangle a4Landscape =
                    new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            PDPage newPage =
                    "PORTRAIT".equals(orientation)
                            ? new PDPage(PDRectangle.A4)
                            : new PDPage(a4Landscape);
            newDocument.addPage(newPage);

            // Use try-with-resources for each content stream to ensure proper cleanup
            // resetContext=true: Start with a clean graphics state for new content
            try (PDPageContentStream contentStream =
                    new PDPageContentStream(
                            newDocument,
                            newPage,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true)) {

                if (addBorder) {
                    contentStream.setLineWidth(borderWidth);
                    contentStream.setStrokingColor(Color.BLACK);
                }

                // Process all pages in this group
                for (int j = 0; j < pagesPerSheet && (i + j) < totalPages; j++) {
                    int pageIndex = i + j;
                    PDPage sourcePage = sourceDocument.getPage(pageIndex);
                    PDRectangle rect = PdfLayouts.displayedSize(sourcePage);
                    float scaleWidth = innerWidth / rect.getWidth();
                    float scaleHeight = innerHeight / rect.getHeight();
                    float scale = Math.min(scaleWidth, scaleHeight);

                    int adjustedPageIndex = j % pagesPerSheet;
                    int rowIndex;
                    int colIndex;

                    if ("BY_ROWS".equals(arrangement)) {
                        rowIndex = adjustedPageIndex / cols;
                        if ("LTR".equals(readingDirection)) {
                            colIndex = adjustedPageIndex % cols;
                        } else {
                            colIndex = cols - 1 - (adjustedPageIndex % cols);
                        }
                    } else {
                        rowIndex = adjustedPageIndex % rows;
                        if ("LTR".equals(readingDirection)) {
                            colIndex = adjustedPageIndex / rows;
                        } else {
                            colIndex = cols - 1 - (adjustedPageIndex / rows);
                        }
                    }

                    float x =
                            leftMargin
                                    + colIndex * cellWidth
                                    + innerMargin
                                    + (innerWidth - rect.getWidth() * scale) / 2;
                    float y =
                            newPage.getMediaBox().getHeight()
                                    - topMargin
                                    - ((rowIndex + 1) * cellHeight
                                            - innerMargin
                                            - (innerHeight - rect.getHeight() * scale) / 2);

                    placements.add(
                            new GeneralFormCopyUtils.Placement(
                                    newDocument.getNumberOfPages() - 1,
                                    x - sourcePage.getCropBox().getLowerLeftX() * scale,
                                    y - sourcePage.getCropBox().getLowerLeftY() * scale,
                                    scale));
                    contentStream.saveGraphicsState();
                    contentStream.transform(Matrix.getTranslateInstance(x, y));
                    contentStream.transform(Matrix.getScaleInstance(scale, scale));

                    PDFormXObject formXObject =
                            PdfLayouts.displayForm(sourceDocument, pageIndex, layerUtility);
                    contentStream.drawForm(formXObject);

                    contentStream.restoreGraphicsState();

                    if (addBorder) {
                        // Draw border around each page
                        contentStream.addRect(
                                x, y, rect.getWidth() * scale, rect.getHeight() * scale);
                        contentStream.stroke();
                    }
                }
            } // contentStream is automatically closed here
        }

        if (!GeneralFormCopyUtils.hasAnyRotatedPage(sourceDocument)) {
            GeneralFormCopyUtils.copyAndTransformFormFields(
                    sourceDocument, newDocument, placements);
        }
    }
}

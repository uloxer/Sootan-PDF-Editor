package stirling.software.SPDF.controller.api;

import java.awt.*;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.model.api.general.BookletImpositionRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@RestController
@RequestMapping("/api/v1/general")
@Tag(name = "General", description = "General APIs")
@RequiredArgsConstructor
public class BookletImpositionController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            value = "/booklet-imposition",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Create a booklet with proper page imposition",
            description =
                    "This operation combines page reordering for booklet printing with multi-page"
                            + " layout. It rearranges pages in the correct order for booklet printing and"
                            + " places multiple pages on each sheet for proper folding and binding.")
    public ResponseEntity<Resource> createBookletImposition(
            @ModelAttribute BookletImpositionRequest request) throws IOException {

        MultipartFile file = request.getFileInput();
        int pagesPerSheet = request.getPagesPerSheet();
        boolean addBorder = Boolean.TRUE.equals(request.getAddBorder());
        String spineLocation =
                request.getSpineLocation() != null ? request.getSpineLocation() : "LEFT";
        boolean addGutter = Boolean.TRUE.equals(request.getAddGutter());
        float gutterSize = request.getGutterSize();
        boolean doubleSided = Boolean.TRUE.equals(request.getDoubleSided());
        String duplexPass = request.getDuplexPass() != null ? request.getDuplexPass() : "BOTH";
        boolean flipOnShortEdge = Boolean.TRUE.equals(request.getFlipOnShortEdge());

        // Validate pages per sheet for booklet - only 2-up landscape is proper booklet
        if (pagesPerSheet != 2) {
            throw new IllegalArgumentException(
                    "Booklet printing uses 2 pages per side (landscape). For 4-up, use the N-up feature.");
        }

        try (PDDocument sourceDocument = pdfDocumentFactory.load(file)) {
            int totalPages = sourceDocument.getNumberOfPages();

            // Create proper booklet with signature-based page ordering
            try (PDDocument newDocument =
                    createSaddleBooklet(
                            sourceDocument,
                            totalPages,
                            addBorder,
                            spineLocation,
                            addGutter,
                            gutterSize,
                            doubleSided,
                            duplexPass,
                            flipOnShortEdge)) {

                return WebResponseUtils.pdfDocToWebResponse(
                        newDocument,
                        GeneralUtils.generateFilename(
                                Filenames.toSimpleFileName(file.getOriginalFilename()),
                                "_booklet.pdf"),
                        tempFileManager);
            }
        }
    }

    private PDDocument createSaddleBooklet(
            PDDocument src,
            int totalPages,
            boolean addBorder,
            String spineLocation,
            boolean addGutter,
            float gutterSize,
            boolean doubleSided,
            String duplexPass,
            boolean flipOnShortEdge)
            throws IOException {
        return stirling.software.pdf.PdfLayouts.booklet(
                src,
                pdfDocumentFactory.createNewDocumentBasedOnOldDocument(src),
                totalPages,
                addBorder,
                spineLocation,
                addGutter,
                gutterSize,
                doubleSided,
                duplexPass,
                flipOnShortEdge);
    }
}

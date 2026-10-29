package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.swagger.MultiFileResponse;
import stirling.software.SPDF.model.api.general.PosterPdfRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.GeneralApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolArity;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@GeneralApi
@Slf4j
@RequiredArgsConstructor
public class PosterPdfController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            value = "/split-for-poster-print",
            consumes = "multipart/form-data",
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @MultiFileResponse
    @ToolIO(produces = ToolFormat.PDF, arity = ToolArity.SIMO)
    @Operation(
            summary = "Split large PDF pages into smaller printable chunks",
            description =
                    "This endpoint splits large or oddly-sized PDF pages into smaller chunks"
                            + " suitable for printing on standard paper sizes (e.g., A4, Letter). Divides each"
                            + " page into a grid of smaller pages using Apache PDFBox.")
    public ResponseEntity<Resource> posterPdf(@ModelAttribute PosterPdfRequest request)
            throws Exception {

        log.debug("Starting PDF poster split process with request: {}", request);
        MultipartFile file = request.getFileInput();

        String filename = GeneralUtils.generateFilename(file.getOriginalFilename(), "");
        log.debug("Base filename for output: {}", filename);

        TempFile zipTempFile = new TempFile(tempFileManager, ".zip");
        try {
            try (PDDocument sourceDocument = pdfDocumentFactory.load(file);
                    PDDocument outputDocument =
                            pdfDocumentFactory.createNewDocumentBasedOnOldDocument(sourceDocument);
                    TempFile pdfTempFile = new TempFile(tempFileManager, ".pdf")) {

                stirling.software.pdf.PdfPoster.apply(
                        sourceDocument,
                        outputDocument,
                        new stirling.software.pdf.PdfPoster.Options(
                                request.getPageSize(),
                                request.getXFactor(),
                                request.getYFactor(),
                                request.isRightToLeft()));

                // Save output PDF to intermediate TempFile
                outputDocument.save(pdfTempFile.getFile());
                log.debug("Generated output PDF with {} pages", outputDocument.getNumberOfPages());

                // Create ZIP from the PDF TempFile, streaming directly to zip TempFile
                try (ZipOutputStream zipOut =
                        new ZipOutputStream(Files.newOutputStream(zipTempFile.getPath()))) {
                    zipOut.putNextEntry(new ZipEntry(filename + "_poster.pdf"));
                    Files.copy(pdfTempFile.getPath(), zipOut);
                    zipOut.closeEntry();
                }
                // pdfTempFile auto-closed and deleted here (end of inner try-with-resources)
            }

            log.debug("Successfully created ZIP");
            return WebResponseUtils.zipFileToWebResponse(zipTempFile, filename + "_poster.zip");

        } catch (IOException e) {
            ExceptionUtils.logException("PDF poster split process", e);
            zipTempFile.close();
            throw e;
        } catch (Exception e) {
            zipTempFile.close();
            throw e;
        }
    }

    /**
     * Maps page size string to PDRectangle.
     *
     * @param pageSize the page size name (e.g., "A4", "Letter")
     * @return the corresponding PDRectangle
     * @throws IllegalArgumentException if page size is not supported
     */
    private PDRectangle getTargetPageSize(String pageSize) {
        return stirling.software.pdf.PdfPoster.pageSize(pageSize);
    }
}

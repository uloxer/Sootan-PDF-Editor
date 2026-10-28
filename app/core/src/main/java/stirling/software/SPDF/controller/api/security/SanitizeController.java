package stirling.software.SPDF.controller.api.security;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.security.SanitizePdfRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.SecurityApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;
import stirling.software.pdf.PdfSanitizer;

@Slf4j
@SecurityApi
@RequiredArgsConstructor
public class SanitizeController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/sanitize-pdf",
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @StandardPdfResponse
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Sanitize a PDF file",
            description =
                    "This endpoint processes a PDF file and removes specific elements based on the"
                            + " provided options.")
    public ResponseEntity<Resource> sanitizePDF(@ModelAttribute SanitizePdfRequest request)
            throws IOException {
        MultipartFile inputFile = request.getFileInput();
        boolean removeJavaScript = Boolean.TRUE.equals(request.getRemoveJavaScript());
        boolean removeEmbeddedFiles = Boolean.TRUE.equals(request.getRemoveEmbeddedFiles());
        boolean removeXMPMetadata = Boolean.TRUE.equals(request.getRemoveXMPMetadata());
        boolean removeMetadata = Boolean.TRUE.equals(request.getRemoveMetadata());
        boolean removeLinks = Boolean.TRUE.equals(request.getRemoveLinks());
        boolean removeFonts = Boolean.TRUE.equals(request.getRemoveFonts());

        try (PDDocument document = pdfDocumentFactory.load(inputFile, true)) {
            if (removeJavaScript) {
                PdfSanitizer.sanitizeJavaScript(document);
            }

            if (removeEmbeddedFiles) {
                PdfSanitizer.sanitizeEmbeddedFiles(document);
            }

            if (removeXMPMetadata) {
                PdfSanitizer.sanitizeXMPMetadata(document);
            }

            if (removeMetadata) {
                PdfSanitizer.sanitizeDocumentInfoMetadata(document);
            }

            if (removeLinks) {
                PdfSanitizer.sanitizeLinks(document);
            }

            if (removeFonts) {
                PdfSanitizer.sanitizeFonts(document);
            }

            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.generateFilename(
                            inputFile.getOriginalFilename(), "_sanitized.pdf"),
                    tempFileManager);
        }
    }
}

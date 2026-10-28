package stirling.software.SPDF.controller.api.security;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.SecurityApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@SecurityApi
@RequiredArgsConstructor
public class RemoveCertSignController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/remove-cert-sign",
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @StandardPdfResponse
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Remove digital signature from PDF",
            description =
                    "This endpoint accepts a PDF file and returns the PDF file without the digital"
                            + " signature.")
    public ResponseEntity<Resource> removeCertSignPDF(@ModelAttribute PDFFile request)
            throws Exception {
        MultipartFile pdf = request.getFileInput();

        // Load the PDF document with proper resource management
        try (PDDocument document = pdfDocumentFactory.load(pdf)) {

            stirling.software.pdf.PdfCertificates.removeSignatures(document);
            // Return the modified PDF as a response
            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.generateFilename(pdf.getOriginalFilename(), "_unsigned.pdf"),
                    tempFileManager);
        }
    }
}

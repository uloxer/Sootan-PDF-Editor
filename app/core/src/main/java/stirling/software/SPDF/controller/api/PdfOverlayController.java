package stirling.software.SPDF.controller.api;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.general.OverlayPdfsRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.GeneralApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolArity;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@GeneralApi
@RequiredArgsConstructor
public class PdfOverlayController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            value = "/overlay-pdfs",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @StandardPdfResponse
    @ToolIO(produces = ToolFormat.PDF, arity = ToolArity.MISO)
    @Operation(
            summary = "Overlay PDF files in various modes",
            description =
                    "Overlay PDF files onto a base PDF with different modes: Sequential,"
                            + " Interleaved, or Fixed Repeat.")
    public ResponseEntity<Resource> overlayPdfs(@ModelAttribute OverlayPdfsRequest request)
            throws IOException {
        MultipartFile baseFile = request.getFileInput();
        MultipartFile[] files = request.getOverlayFiles();
        if (files == null || files.length == 0)
            throw new IllegalArgumentException("At least one overlay file is required");
        var documents = new java.util.ArrayList<PDDocument>();
        TempFile output = null;
        try (PDDocument base = pdfDocumentFactory.load(baseFile)) {
            for (var file : files) {
                if (file == null || file.isEmpty())
                    throw new IllegalArgumentException("Overlay files must not be empty");
                documents.add(pdfDocumentFactory.load(file));
            }
            output = tempFileManager.createManagedTempFile(".pdf");
            stirling.software.pdf.PdfOverlays.apply(
                    base,
                    documents,
                    request.getOverlayMode(),
                    request.getCounts(),
                    request.getOverlayPosition(),
                    output.getPath());
            TempFile response = output;
            output = null;
            return WebResponseUtils.pdfFileToWebResponse(
                    response,
                    GeneralUtils.generateFilename(
                            baseFile.getOriginalFilename(), "_overlayed.pdf"));
        } finally {
            if (output != null) output.close();
            for (var document : documents) document.close();
        }
    }
}

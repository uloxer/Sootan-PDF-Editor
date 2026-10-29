package stirling.software.SPDF.controller.api.misc;

import java.io.IOException;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@MiscApi
@Slf4j
@RequiredArgsConstructor
public class DecompressPdfController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            value = "/decompress-pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Decompress PDF streams",
            description = "Fully decompresses all PDF streams including text content")
    public ResponseEntity<Resource> decompressPdf(@ModelAttribute PDFFile request)
            throws IOException {

        MultipartFile file = request.getFileInput();

        try (PDDocument document = pdfDocumentFactory.load(file)) {
            // Process all objects in document
            stirling.software.pdf.PdfRecovery.decompress(document);

            // Save with explicit no compression to a temp file
            TempFile tempOut = tempFileManager.createManagedTempFile(".pdf");
            try {
                document.save(tempOut.getFile(), CompressParameters.NO_COMPRESSION);
            } catch (IOException e) {
                tempOut.close();
                throw e;
            }

            // Return the PDF as a streaming response
            return WebResponseUtils.pdfFileToWebResponse(
                    tempOut,
                    GeneralUtils.generateFilename(file.getOriginalFilename(), "_decompressed.pdf"));
        }
    }
}

package stirling.software.SPDF.controller.api.misc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.JavaScriptResponse;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@MiscApi
@RequiredArgsConstructor
public class ShowJavascript {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/show-javascript",
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JavaScriptResponse
    @ToolIO(produces = ToolFormat.JAVASCRIPT)
    @Operation(
            summary = "Grabs all JS from a PDF and returns a single JS file with all code",
            description = "desc.")
    public ResponseEntity<Resource> extractHeader(@ModelAttribute PDFFile file) throws Exception {
        MultipartFile inputFile = file.getFileInput();
        try (PDDocument document = pdfDocumentFactory.load(inputFile)) {

            String script =
                    stirling.software.pdf.PdfDocumentAnalysis.javascript(
                            document, Filenames.toSimpleFileName(inputFile.getOriginalFilename()));
            TempFile tempOut = tempFileManager.createManagedTempFile(".js");
            try {
                Files.write(
                        tempOut.getFile().toPath(),
                        script.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                tempOut.close();
                throw e;
            }
            return WebResponseUtils.fileToWebResponse(
                    tempOut,
                    Filenames.toSimpleFileName(inputFile.getOriginalFilename()) + ".js",
                    MediaType.TEXT_PLAIN);
        }
    }
}

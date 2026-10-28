package stirling.software.SPDF.controller.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.action.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.security.PDFVerificationResult;
import stirling.software.SPDF.service.VeraPDFService;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.SecurityApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.WebResponseUtils;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SecurityApi
@Slf4j
@RequiredArgsConstructor
public class GetInfoOnPDF extends stirling.software.pdf.PdfDocumentInfo {
    private static final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final VeraPDFService veraPDFService;

    private static ResponseEntity<byte[]> createErrorResponse(String errorMessage) {
        try {
            ObjectNode errorNode = objectMapper.createObjectNode();
            errorNode.put("error", errorMessage);
            errorNode.put("timestamp", System.currentTimeMillis());

            String jsonString =
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorNode);
            return WebResponseUtils.bytesToWebResponse(
                    jsonString.getBytes(StandardCharsets.UTF_8),
                    "error.json",
                    MediaType.APPLICATION_JSON);
        } catch (Exception e) {
            log.error("Failed to create error response", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/get-info-on-pdf",
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @ToolIO(produces = ToolFormat.JSON)
    @Operation(
            summary = "Get comprehensive PDF information",
            description = "Extracts all available information from a PDF file.")
    public ResponseEntity<byte[]> getPdfInfo(@ModelAttribute PDFFile request) throws IOException {
        MultipartFile inputFile = request.getFileInput();

        List<PDFVerificationResult> verificationResults = null;
        try {
            verificationResults = veraPDFService.validatePDF(inputFile.getInputStream());
        } catch (Exception e) {
            log.error("VeraPDF validation failed", e);
        }

        boolean readonly = true;

        try (PDDocument pdfBoxDoc = pdfDocumentFactory.load(inputFile, readonly)) {
            ObjectNode jsonOutput = inspect(pdfBoxDoc, inputFile.getSize(), verificationResults);
            // Convert to JSON string
            String jsonString =
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonOutput);

            return WebResponseUtils.bytesToWebResponse(
                    jsonString.getBytes(StandardCharsets.UTF_8),
                    "response.json",
                    MediaType.APPLICATION_JSON);

        } catch (IOException e) {
            log.error("IO error while processing PDF: {}", e.getMessage(), e);
            return createErrorResponse("Error reading PDF file: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error while processing PDF: {}", e.getMessage(), e);
            return createErrorResponse("Unexpected error processing PDF: " + e.getMessage());
        }
    }
}

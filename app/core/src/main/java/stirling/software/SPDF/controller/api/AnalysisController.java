package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.util.*;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.JsonDataResponse;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.AnalysisApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;

@AnalysisApi
@RequiredArgsConstructor
public class AnalysisController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;

    @AutoJobPostMapping(
            value = "/page-count",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JsonDataResponse
    @Operation(
            summary = "Get PDF page count",
            description = "Returns total number of pages in PDF.")
    public ResponseEntity<?> getPageCount(@ModelAttribute PDFFile file) throws IOException {
        try (PDDocument document = pdfDocumentFactory.load(file.getFileInput())) {
            return ResponseEntity.ok(
                    stirling.software.pdf.PdfDocumentAnalysis.getPageCount(
                            document, file.getFileInput().getSize()));
        }
    }

    @AutoJobPostMapping(
            value = "/basic-info",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JsonDataResponse
    @Operation(
            summary = "Get basic PDF information",
            description = "Returns page count, version, file size.")
    public ResponseEntity<?> getBasicInfo(@ModelAttribute PDFFile file) throws IOException {
        try (PDDocument document = pdfDocumentFactory.load(file.getFileInput())) {
            return ResponseEntity.ok(
                    stirling.software.pdf.PdfDocumentAnalysis.getBasicInfo(
                            document, file.getFileInput().getSize()));
        }
    }

    @AutoJobPostMapping(
            value = "/document-properties",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JsonDataResponse
    @Operation(
            summary = "Get PDF document properties",
            description = "Returns title, author, subject, etc.")
    public ResponseEntity<?> getDocumentProperties(@ModelAttribute PDFFile file)
            throws IOException {
        try (PDDocument document = pdfDocumentFactory.load(file.getFileInput(), true)) {
            return ResponseEntity.ok(
                    stirling.software.pdf.PdfDocumentAnalysis.getDocumentProperties(
                            document, file.getFileInput().getSize()));
        }
    }

    @AutoJobPostMapping(
            value = "/page-dimensions",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JsonDataResponse
    @Operation(
            summary = "Get page dimensions for all pages",
            description = "Returns width and height of each page.")
    public ResponseEntity<?> getPageDimensions(@ModelAttribute PDFFile file) throws IOException {
        try (PDDocument document = pdfDocumentFactory.load(file.getFileInput())) {
            return ResponseEntity.ok(
                    stirling.software.pdf.PdfDocumentAnalysis.getPageDimensions(
                            document, file.getFileInput().getSize()));
        }
    }

    @AutoJobPostMapping(
            value = "/form-fields",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JsonDataResponse
    @Operation(
            summary = "Get form field information",
            description = "Returns count and details of form fields.")
    public ResponseEntity<?> getFormFields(@ModelAttribute PDFFile file) throws IOException {
        try (PDDocument document = pdfDocumentFactory.load(file.getFileInput())) {
            return ResponseEntity.ok(
                    stirling.software.pdf.PdfDocumentAnalysis.getFormFields(
                            document, file.getFileInput().getSize()));
        }
    }

    @AutoJobPostMapping(
            value = "/annotation-info",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JsonDataResponse
    @Operation(
            summary = "Get annotation information",
            description = "Returns count and types of annotations.")
    public ResponseEntity<?> getAnnotationInfo(@ModelAttribute PDFFile file) throws IOException {
        try (PDDocument document = pdfDocumentFactory.load(file.getFileInput())) {
            return ResponseEntity.ok(
                    stirling.software.pdf.PdfDocumentAnalysis.getAnnotationInfo(
                            document, file.getFileInput().getSize()));
        }
    }

    @AutoJobPostMapping(
            value = "/font-info",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JsonDataResponse
    @Operation(
            summary = "Get font information",
            description = "Returns list of fonts used in the document.")
    public ResponseEntity<?> getFontInfo(@ModelAttribute PDFFile file) throws IOException {
        try (PDDocument document = pdfDocumentFactory.load(file.getFileInput())) {
            return ResponseEntity.ok(
                    stirling.software.pdf.PdfDocumentAnalysis.getFontInfo(
                            document, file.getFileInput().getSize()));
        }
    }

    @AutoJobPostMapping(
            value = "/security-info",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @JsonDataResponse
    @Operation(
            summary = "Get security information",
            description = "Returns encryption and permission details.")
    public ResponseEntity<?> getSecurityInfo(@ModelAttribute PDFFile file) throws IOException {
        try (PDDocument document = pdfDocumentFactory.load(file.getFileInput())) {
            return ResponseEntity.ok(
                    stirling.software.pdf.PdfDocumentAnalysis.getSecurityInfo(
                            document, file.getFileInput().getSize()));
        }
    }
}

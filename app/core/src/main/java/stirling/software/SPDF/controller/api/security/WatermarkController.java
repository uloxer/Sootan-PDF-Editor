package stirling.software.SPDF.controller.api.security;

import java.awt.*;
import java.beans.PropertyEditorSupport;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.security.AddWatermarkRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.SecurityApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.PdfUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@SecurityApi
@RequiredArgsConstructor
public class WatermarkController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(
                MultipartFile.class,
                new PropertyEditorSupport() {
                    @Override
                    public void setAsText(String text) throws IllegalArgumentException {
                        setValue(null);
                    }
                });
    }

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/add-watermark",
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @StandardPdfResponse
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Add watermark to a PDF file",
            description =
                    "This endpoint adds a watermark to a given PDF file. Users can specify the"
                            + " watermark type (text or image), rotation, opacity, width spacer, and height"
                            + " spacer.")
    public ResponseEntity<Resource> addWatermark(@Valid @ModelAttribute AddWatermarkRequest request)
            throws IOException, Exception {
        MultipartFile pdfFile = request.getFileInput();
        String pdfFileName = pdfFile.getOriginalFilename();
        if (pdfFileName != null && (pdfFileName.contains("..") || pdfFileName.startsWith("/"))) {
            throw new SecurityException("Invalid file path in pdfFile");
        }
        String watermarkType = request.getWatermarkType();
        String watermarkText = request.getWatermarkText();
        MultipartFile watermarkImage = request.getWatermarkImage();
        if (watermarkImage != null) {
            String watermarkImageFileName = watermarkImage.getOriginalFilename();
            if (watermarkImageFileName != null
                    && (watermarkImageFileName.contains("..")
                            || watermarkImageFileName.startsWith("/"))) {
                throw new SecurityException("Invalid file path in watermarkImage");
            }
        }
        String alphabet = request.getAlphabet();
        float fontSize = request.getFontSize();
        float rotation = request.getRotation();
        float opacity = request.getOpacity();
        int widthSpacer = request.getWidthSpacer();
        int heightSpacer = request.getHeightSpacer();
        String customColor = request.getCustomColor();
        boolean convertPdfToImage = Boolean.TRUE.equals(request.getConvertPDFToImage());

        // Load the input PDF with proper resource management
        try (PDDocument document = pdfDocumentFactory.load(pdfFile)) {

            stirling.software.pdf.PdfWatermarks.apply(
                    document,
                    watermarkType,
                    watermarkText,
                    watermarkImage == null ? null : ImageIO.read(watermarkImage.getInputStream()),
                    alphabet,
                    fontSize,
                    rotation,
                    opacity,
                    widthSpacer,
                    heightSpacer,
                    customColor);
            if (convertPdfToImage) {
                try (PDDocument convertedPdf = PdfUtils.convertPdfToPdfImage(document)) {
                    // Return the watermarked PDF as a response
                    return WebResponseUtils.pdfDocToWebResponse(
                            convertedPdf,
                            GeneralUtils.generateFilename(
                                    pdfFile.getOriginalFilename(), "_watermarked.pdf"),
                            tempFileManager);
                }
            } else {
                // Return the watermarked PDF as a response
                return WebResponseUtils.pdfDocToWebResponse(
                        document,
                        GeneralUtils.generateFilename(
                                pdfFile.getOriginalFilename(), "_watermarked.pdf"),
                        tempFileManager);
            }
        }
    }

    /**
     * Load the watermark font once for the whole document. {@link PDType0Font#load} embeds a font
     * object into {@code document} on every call, so calling this per page holds one full copy of
     * the TTF per page in memory (10 MB each for the CJK faces).
     */
}

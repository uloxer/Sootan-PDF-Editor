package stirling.software.SPDF.controller.api.misc;

import java.awt.*;
import java.beans.PropertyEditorSupport;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

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

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.model.api.misc.AddStampRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@MiscApi
@RequiredArgsConstructor
public class StampController extends stirling.software.pdf.PdfStamp {

    private static final Pattern NEWLINE_PATTERN = Pattern.compile("\\r?\\n");
    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    private static final int MAX_DATE_FORMAT_LENGTH = 50;
    private static final Pattern SAFE_DATE_FORMAT_PATTERN =
            Pattern.compile("^[yMdHhmsS/\\-:\\s.,'+EGuwWDFzZXa]+$");
    private static final Pattern CUSTOM_DATE_PATTERN = Pattern.compile("@date\\{([^}]{1,50})\\}");
    // Placeholder for escaped @ symbol (using Unicode private use area)
    private static final String ESCAPED_AT_PLACEHOLDER = "\uE000ESCAPED_AT\uE000";
    // Counted in UTF-8 bytes, the unit QR byte mode is limited by. Must stay in sync with
    // MAX_QR_CONTENT_BYTES in the frontend's useAddStampParameters.ts.
    private static final int MAX_QR_CONTENT_BYTES = 1000;
    private static final int QR_QUIET_ZONE_MODULES = 4;

    /**
     * Initialize data binder for multipart file uploads. This method registers a custom editor for
     * MultipartFile to handle file uploads. It sets the MultipartFile to null if the uploaded file
     * is empty. This is necessary to avoid binding errors when the file is not present.
     */
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
            value = "/add-stamp",
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Add stamp to a PDF file",
            description =
                    "This endpoint adds a stamp to a given PDF file. Users can specify the stamp"
                            + " type (text, image or qr), rotation, opacity, width spacer, and height spacer.")
    public ResponseEntity<Resource> addStamp(@ModelAttribute AddStampRequest request)
            throws IOException, Exception {
        MultipartFile pdfFile = request.getFileInput();
        String pdfFileName = pdfFile.getOriginalFilename();
        if (pdfFileName.contains("..") || pdfFileName.startsWith("/")) {
            throw ExceptionUtils.createIllegalArgumentException(
                    "error.invalid.filepath", "Invalid PDF file path: " + pdfFileName);
        }

        String stampType = request.getStampType();
        String stampText = request.getStampText();
        MultipartFile stampImage = request.getStampImage();
        if ("image".equalsIgnoreCase(stampType)) {
            if (stampImage == null) {
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.stamp.image.required",
                        "Stamp image file must be provided when stamp type is 'image'");
            }
            String stampImageName = stampImage.getOriginalFilename();
            if (stampImageName == null
                    || stampImageName.contains("..")
                    || stampImageName.startsWith("/")) {
                throw ExceptionUtils.createIllegalArgumentException(
                        "error.invalidFormat",
                        "Invalid {0} format: {1}",
                        "stamp image file path",
                        stampImageName);
            }
        } else if ("qr".equalsIgnoreCase(stampType)) {
            requireEncodableQrContent(stampText);
        }
        String alphabet = request.getAlphabet();
        float fontSize = request.getFontSize();
        float rotation = request.getRotation();
        float opacity = request.getOpacity();
        int position = request.getPosition(); // Updated to use 1-9 positioning logic
        float overrideX = request.getOverrideX(); // New field for X override
        float overrideY = request.getOverrideY(); // New field for Y override

        String customColor = request.getCustomColor();
        float marginFactor =
                switch (request.getCustomMargin().toLowerCase(Locale.ROOT)) {
                    case "small" -> 0.02f;
                    case "medium" -> 0.035f;
                    case "large" -> 0.05f;
                    case "x-large" -> 0.075f;
                    default -> 0.035f;
                };

        // Load the input PDF
        try (PDDocument document = pdfDocumentFactory.load(pdfFile)) {

            apply(
                    document,
                    request.getPageNumbersList(document, true),
                    pdfFileName,
                    stampType,
                    stampText,
                    stampImage == null ? null : ImageIO.read(stampImage.getInputStream()),
                    alphabet,
                    fontSize,
                    rotation,
                    opacity,
                    position,
                    overrideX,
                    overrideY,
                    customColor,
                    request.getCustomMargin());
            // Return the stamped PDF as a response
            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.generateFilename(pdfFile.getOriginalFilename(), "_stamped.pdf"),
                    tempFileManager);
        }
    }

    private static void requireEncodableQrContent(String content) {
        stirling.software.pdf.PdfStamp.validateQr(content);
    }
}

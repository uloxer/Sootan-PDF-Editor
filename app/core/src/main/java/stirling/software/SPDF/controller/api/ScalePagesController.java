package stirling.software.SPDF.controller.api;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.general.ScalePagesRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.GeneralApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;
import stirling.software.pdf.PdfPageScale;

@GeneralApi
@Slf4j
@RequiredArgsConstructor
public class ScalePagesController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    private static PDRectangle getTargetSize(
            String targetPDRectangle, String orientation, PDDocument sourceDocument) {
        if ("KEEP".equals(targetPDRectangle)) {
            if (sourceDocument.getNumberOfPages() == 0) {
                throw ExceptionUtils.createPdfNoPages();
            }

            PDPage sourcePage = sourceDocument.getPage(0);
            PDRectangle sourceSize = sourcePage.getMediaBox();

            if (sourceSize == null) {
                throw ExceptionUtils.createInvalidPageSizeException("KEEP");
            }

            return sourceSize;
        }

        Map<String, PDRectangle> sizeMap = getSizeMap();
        PDRectangle base = sizeMap.get(targetPDRectangle);
        if (base == null) {
            throw ExceptionUtils.createInvalidPageSizeException(targetPDRectangle);
        }

        if ("LANDSCAPE".equalsIgnoreCase(orientation)) {
            return new PDRectangle(base.getHeight(), base.getWidth());
        }
        return base;
    }

    private static Map<String, PDRectangle> getSizeMap() {
        Map<String, PDRectangle> sizeMap = new HashMap<>();
        sizeMap.put("A0", PDRectangle.A0);
        sizeMap.put("A1", PDRectangle.A1);
        sizeMap.put("A2", PDRectangle.A2);
        sizeMap.put("A3", PDRectangle.A3);
        sizeMap.put("A4", PDRectangle.A4);
        sizeMap.put("A5", PDRectangle.A5);
        sizeMap.put("A6", PDRectangle.A6);
        sizeMap.put("LETTER", PDRectangle.LETTER);
        sizeMap.put("LEGAL", PDRectangle.LEGAL);
        return sizeMap;
    }

    @AutoJobPostMapping(
            value = "/scale-pages",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Change the size of a PDF page/document",
            description =
                    "This operation takes an input PDF file and the size to scale the pages to in"
                            + " the output PDF file.")
    public ResponseEntity<Resource> scalePages(@ModelAttribute ScalePagesRequest request)
            throws IOException {
        MultipartFile file = request.getFileInput();
        String targetPDRectangle = request.getPageSize();
        String orientation = request.getOrientation();
        float scaleFactor = request.getScaleFactor();

        try (PDDocument sourceDocument = pdfDocumentFactory.load(file);
                PDDocument outputDocument =
                        pdfDocumentFactory.createNewDocumentBasedOnOldDocument(sourceDocument)) {

            PdfPageScale.apply(
                    sourceDocument, outputDocument, targetPDRectangle, orientation, scaleFactor);
            return WebResponseUtils.pdfDocToWebResponse(
                    outputDocument,
                    GeneralUtils.generateFilename(file.getOriginalFilename(), "_scaled.pdf"),
                    tempFileManager);
        }
    }

    private static void flattenStampAppearances(PDDocument document, PDPage page)
            throws IOException {
        var stamps =
                page.getAnnotations().stream()
                        .filter(annotation -> annotation instanceof PDAnnotationRubberStamp)
                        .filter(annotation -> !annotation.isHidden() && !annotation.isNoView())
                        .toList();
        if (stamps.isEmpty()) return;

        // Sign saves locked stamp annotations, which importPageAsForm omits.
        // Embed their appearance before import so the same crop/rotation/scale applies to both.
        try (PDPageContentStream content =
                new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            for (var stamp : stamps) {
                PDAppearanceStream appearance = stamp.getNormalAppearanceStream();
                PDRectangle rect = stamp.getRectangle();
                if (appearance == null
                        || appearance.getBBox() == null
                        || rect == null
                        || rect.getWidth() <= 0
                        || rect.getHeight() <= 0) {
                    continue;
                }
                Rectangle2D bounds =
                        appearance.getBBox().transform(appearance.getMatrix()).getBounds2D();
                if (bounds.isEmpty()) continue;

                Matrix transform =
                        Matrix.getTranslateInstance(rect.getLowerLeftX(), rect.getLowerLeftY());
                transform.scale(
                        (float) (rect.getWidth() / bounds.getWidth()),
                        (float) (rect.getHeight() / bounds.getHeight()));
                transform.translate((float) -bounds.getX(), (float) -bounds.getY());
                PDPropertyList optionalContent = stamp.getOptionalContent();
                if (optionalContent != null) {
                    content.beginMarkedContent(COSName.OC, optionalContent);
                }
                content.saveGraphicsState();
                content.transform(transform);
                content.drawForm(appearance);
                content.restoreGraphicsState();
                if (optionalContent != null) {
                    content.endMarkedContent();
                }
            }
        }
    }
}

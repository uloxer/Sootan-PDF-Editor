package stirling.software.common.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.CustomPDFDocumentFactory;

@Slf4j
@UtilityClass
public class PdfUtils {

    // Print-quality raster for flattening a page to an image; capped by system maxDPI
    private static final int PDF_TO_IMAGE_DPI = 300;

    private final RegexPatternUtils patternCache = RegexPatternUtils.getInstance();

    public PDRectangle textToPageSize(String size) {

        return switch (size.toUpperCase(Locale.ROOT)) {
            case "A0" -> PDRectangle.A0;
            case "A1" -> PDRectangle.A1;
            case "A2" -> PDRectangle.A2;
            case "A3" -> PDRectangle.A3;
            case "A4" -> PDRectangle.A4;
            case "A5" -> PDRectangle.A5;
            case "A6" -> PDRectangle.A6;
            case "LETTER" -> PDRectangle.LETTER;
            case "LEGAL" -> PDRectangle.LEGAL;
            default -> throw ExceptionUtils.createInvalidPageSizeException(size);
        };
    }

    public List<RenderedImage> getAllImages(PDResources resources) throws IOException {
        List<RenderedImage> images = new ArrayList<>();

        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);

            if (object instanceof PDImageXObject) {
                images.add(((PDImageXObject) object).getImage());

            } else if (object instanceof PDFormXObject) {
                images.addAll(getAllImages(((PDFormXObject) object).getResources()));
            }
        }

        return images;
    }

    public boolean hasImages(PDDocument document, String pagesToCheck) throws IOException {
        String[] pageOrderArr = pagesToCheck.split(",");
        List<Integer> pageList =
                GeneralUtils.parsePageList(pageOrderArr, document.getNumberOfPages());

        for (int pageNumber : pageList) {
            PDPage page = document.getPage(pageNumber);
            if (hasImagesOnPage(page)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasText(PDDocument document, String pageNumbersToCheck, String phrase)
            throws IOException {
        String[] pageOrderArr = pageNumbersToCheck.split(",");
        List<Integer> pageList =
                GeneralUtils.parsePageList(pageOrderArr, document.getNumberOfPages());

        for (int pageNumber : pageList) {
            PDPage page = document.getPage(pageNumber);
            if (hasTextOnPage(page, phrase)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasImagesOnPage(PDPage page) throws IOException {
        return !getAllImages(page.getResources()).isEmpty();
    }

    public boolean hasTextOnPage(PDPage page, String phrase) throws IOException {
        PDFTextStripper textStripper = new PDFTextStripper();
        try (PDDocument tempDoc = new PDDocument()) {
            tempDoc.addPage(page);
            String pageText = textStripper.getText(tempDoc);
            return pageText.contains(phrase);
        }
    }

    public byte[] convertFromPdf(
            CustomPDFDocumentFactory factory,
            byte[] bytes,
            String type,
            ImageType color,
            boolean single,
            int dpi,
            String filename,
            boolean annotations)
            throws Exception {
        int max = 500;
        var properties = ApplicationContextProvider.getBean(ApplicationProperties.class);
        if (properties != null && properties.getSystem() != null)
            max = properties.getSystem().getMaxDPI();
        if (dpi > max) throw new IllegalArgumentException("DPI exceeds configured maximum.");
        try (var document = factory.load(bytes)) {
            return stirling.software.pdf.PdfImageConversion.render(
                    document, type, color, single, dpi, filename, annotations);
        }
    }

    /**
     * Converts a given Pdf file to PDF-Image.
     *
     * @param document to be converted. Note: the caller is responsible for closing the document
     * @return converted document to PDF-Image
     * @throws IOException if conversion fails
     */
    public PDDocument convertPdfToPdfImage(PDDocument document) throws IOException {
        PDDocument imageDocument = new PDDocument();
        try {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            pdfRenderer.setSubsamplingAllowed(true);
            for (int page = 0; page < document.getNumberOfPages(); ++page) {
                final int pageIndex = page;
                BufferedImage bim;

                // maxDPI is a safety ceiling for user-supplied values, not a target resolution
                int renderDpi = PDF_TO_IMAGE_DPI;
                ApplicationProperties properties =
                        ApplicationContextProvider.getBean(ApplicationProperties.class);
                if (properties != null && properties.getSystem() != null) {
                    renderDpi = Math.min(renderDpi, properties.getSystem().getMaxDPI());
                }
                final int dpi = renderDpi;

                try {
                    bim =
                            ExceptionUtils.handleOomRendering(
                                    pageIndex + 1,
                                    dpi,
                                    () ->
                                            pdfRenderer.renderImageWithDPI(
                                                    pageIndex, dpi, ImageType.RGB));
                } catch (IllegalArgumentException e) {
                    if (e.getMessage() != null
                            && e.getMessage().contains("Maximum size of image exceeded")) {
                        throw ExceptionUtils.createIllegalArgumentException(
                                "error.pageTooBigFor300Dpi",
                                "PDF page {0} is too large to render at 300 DPI. The resulting image"
                                        + " would exceed Java's maximum array size. Please use a lower DPI"
                                        + " value for PDF-to-image conversion.",
                                pageIndex + 1);
                    }
                    throw e;
                }
                PDPage originalPage = document.getPage(page);

                float width = originalPage.getMediaBox().getWidth();
                float height = originalPage.getMediaBox().getHeight();

                PDPage newPage = new PDPage(new PDRectangle(width, height));
                imageDocument.addPage(newPage);
                PDImageXObject pdImage = LosslessFactory.createFromImage(imageDocument, bim);
                try (PDPageContentStream contentStream =
                        new PDPageContentStream(
                                imageDocument, newPage, AppendMode.APPEND, true, true)) {
                    contentStream.drawImage(pdImage, 0, 0, width, height);
                }
                bim.flush();
            }
            return imageDocument;
        } catch (Exception e) {
            throw e;
        }
    }

    private BufferedImage prepareImageForPdfToImage(int maxWidth, int height, String imageType) {
        BufferedImage combined;
        if ("png".equalsIgnoreCase(imageType)) {
            combined = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_ARGB);
        } else {
            combined = new BufferedImage(maxWidth, height, BufferedImage.TYPE_INT_RGB);
        }
        if (!"png".equalsIgnoreCase(imageType)) {
            Graphics g = combined.getGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, combined.getWidth(), combined.getHeight());
            g.dispose();
        }
        return combined;
    }

    public byte[] imageToPdf(
            MultipartFile[] files,
            String fitOption,
            boolean autoRotate,
            String colorType,
            CustomPDFDocumentFactory pdfDocumentFactory)
            throws IOException {
        try (PDDocument doc = pdfDocumentFactory.createNewDocument()) {
            for (MultipartFile file : files) {
                if (isTiff(file)) {
                    appendTiffFrames(doc, file, fitOption, autoRotate, colorType);
                } else {
                    appendSingleImage(doc, file, fitOption, autoRotate, colorType);
                }
            }
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            doc.save(byteArrayOutputStream);
            log.debug("PDF successfully saved to byte array");
            return byteArrayOutputStream.toByteArray();
        }
    }

    /**
     * A TIFF is recognised by declared content type as well as filename suffix: an extensionless
     * upload would otherwise take the single-image path and silently drop every frame but one.
     */
    private boolean isTiff(MultipartFile file) {
        if (hasTiffContentType(file.getContentType())) {
            return true;
        }
        String filename = Filenames.toSimpleFileName(file.getOriginalFilename());
        return filename != null
                && (filename.toLowerCase(Locale.ROOT).endsWith(".tiff")
                        || filename.toLowerCase(Locale.ROOT).endsWith(".tif"));
    }

    /** Parsed, not compared whole: RFC 3302 allows parameters such as {@code application=}. */
    private boolean hasTiffContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return "image".equals(mediaType.getType())
                    && ("tiff".equals(mediaType.getSubtype())
                            || "x-tiff".equals(mediaType.getSubtype()));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }

    /** Every frame becomes a page; streams and reader are released even when a frame fails. */
    private void appendTiffFrames(
            PDDocument doc,
            MultipartFile file,
            String fitOption,
            boolean autoRotate,
            String colorType)
            throws IOException {
        try (InputStream input = file.getInputStream();
                ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("tiff").next();
            try {
                reader.setInput(imageInput);
                int numPages = reader.getNumImages(true);
                for (int i = 0; i < numPages; i++) {
                    BufferedImage convertedImage =
                            ImageProcessingUtils.convertColorType(reader.read(i), colorType);
                    PDImageXObject pdImage = LosslessFactory.createFromImage(doc, convertedImage);
                    addImageToDocument(doc, pdImage, fitOption, autoRotate);
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private void appendSingleImage(
            PDDocument doc,
            MultipartFile file,
            String fitOption,
            boolean autoRotate,
            String colorType)
            throws IOException {
        BufferedImage image = ImageProcessingUtils.loadImageWithExifOrientation(file);
        BufferedImage convertedImage = ImageProcessingUtils.convertColorType(image, colorType);
        // Use JPEGFactory if it's JPEG since JPEG is lossy
        String contentType = file.getContentType();
        PDImageXObject pdImage =
                (contentType != null && MediaType.IMAGE_JPEG_VALUE.equals(contentType))
                        ? JPEGFactory.createFromImage(doc, convertedImage)
                        : LosslessFactory.createFromImage(doc, convertedImage);
        addImageToDocument(doc, pdImage, fitOption, autoRotate);
    }

    public void addImageToDocument(
            PDDocument doc, PDImageXObject image, String fitOption, boolean autoRotate)
            throws IOException {
        stirling.software.pdf.PdfImageConversion.addImageToDocument(
                doc, image, fitOption, autoRotate);
    }

    public byte[] overlayImage(
            CustomPDFDocumentFactory pdfDocumentFactory,
            byte[] pdfBytes,
            byte[] imageBytes,
            float x,
            float y,
            boolean everyPage)
            throws IOException {

        try (PDDocument document = pdfDocumentFactory.load(pdfBytes)) {
            // Get the first page of the PDF
            int pages = document.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                PDPage page = document.getPage(i);
                try (PDPageContentStream contentStream =
                        new PDPageContentStream(
                                document,
                                page,
                                PDPageContentStream.AppendMode.APPEND,
                                true,
                                true)) {
                    // Create an image object from the image bytes
                    PDImageXObject image =
                            PDImageXObject.createFromByteArray(document, imageBytes, "");
                    // Draw the image onto the page at the specified x and y coordinates
                    contentStream.drawImage(image, x, y);
                    log.info("Image successfully overlaid onto PDF");
                    if (!everyPage && i == 0) {
                        break;
                    }
                } catch (IOException e) {
                    // Log an error message if there is an issue overlaying the image onto the PDF
                    log.error("Error overlaying image onto PDF", e);
                    throw e;
                }
            }
            // Create a ByteArrayOutputStream to save the PDF to
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            log.info("PDF successfully saved to byte array");
            return baos.toByteArray();
        }
    }

    public boolean containsTextInFile(PDDocument pdfDocument, String text, String pagesToCheck)
            throws IOException {
        PDFTextStripper textStripper = new PDFTextStripper();
        StringBuilder pdfText = new StringBuilder();

        if (pagesToCheck == null || "all".equals(pagesToCheck)) {
            pdfText = new StringBuilder(textStripper.getText(pdfDocument));
        } else {
            // remove whitespaces using cached pattern
            Pattern whitespacePattern =
                    patternCache.getPattern(RegexPatternUtils.getWhitespaceRegex());
            Matcher whitespaceMatcher = whitespacePattern.matcher(pagesToCheck);
            pagesToCheck = whitespaceMatcher.replaceAll("");

            String[] splitPoints = pagesToCheck.split(",");
            for (String splitPoint : splitPoints) {
                if (splitPoint.contains("-")) {
                    // Handle page ranges
                    String[] range = splitPoint.split("-");
                    int startPage = Integer.parseInt(range[0]);
                    int endPage = Integer.parseInt(range[1]);

                    for (int i = startPage; i <= endPage; i++) {
                        textStripper.setStartPage(i);
                        textStripper.setEndPage(i);
                        pdfText.append(textStripper.getText(pdfDocument));
                    }
                } else {
                    // Handle individual page
                    int page = Integer.parseInt(splitPoint);
                    textStripper.setStartPage(page);
                    textStripper.setEndPage(page);
                    pdfText.append(textStripper.getText(pdfDocument));
                }
            }
        }

        pdfDocument.close();

        return pdfText.toString().contains(text);
    }

    public boolean pageCount(PDDocument pdfDocument, int pageCount, String comparator)
            throws IOException {
        int actualPageCount = pdfDocument.getNumberOfPages();
        pdfDocument.close();

        return switch (comparator.toLowerCase(Locale.ROOT)) {
            case "greater" -> actualPageCount > pageCount;
            case "equal" -> actualPageCount == pageCount;
            case "less" -> actualPageCount < pageCount;
            default ->
                    throw ExceptionUtils.createInvalidArgumentException("comparator", comparator);
        };
    }

    public boolean pageSize(PDDocument pdfDocument, String expectedPageSize) throws IOException {
        PDPage firstPage = pdfDocument.getPage(0);
        PDRectangle mediaBox = firstPage.getMediaBox();

        float actualPageWidth = mediaBox.getWidth();
        float actualPageHeight = mediaBox.getHeight();

        pdfDocument.close();

        // Assumes the expectedPageSize is in the format "widthxheight", e.g. "595x842"
        // for A4
        String[] dimensions = expectedPageSize.split("x");
        float expectedPageWidth = Float.parseFloat(dimensions[0]);
        float expectedPageHeight = Float.parseFloat(dimensions[1]);

        // Checks if the actual page size matches the expected page size
        return actualPageWidth == expectedPageWidth && actualPageHeight == expectedPageHeight;
    }

    /** Key for storing the dimensions of a rendered image in a map. */
    private record PdfRenderSettingsKey(float mediaBoxWidth, float mediaBoxHeight, int rotation) {}

    /** Value for storing the dimensions of a rendered image in a map. */
    private record PdfImageDimensionValue(int width, int height) {}
}

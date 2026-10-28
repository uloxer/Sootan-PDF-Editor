package stirling.software.pdf;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/** Retained text/date/image/QR stamp semantics over caller-owned document and bitmap grants. */
public class PdfStamp {
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("\\r?\\n");

    private static final int MAX_DATE_FORMAT_LENGTH = 50;
    private static final Pattern SAFE_DATE_FORMAT_PATTERN =
            Pattern.compile("^[yMdHhmsS/\\-:\\s.,'+EGuwWDFzZXa]+$");
    private static final Pattern CUSTOM_DATE_PATTERN = Pattern.compile("@date\\{([^}]{1,50})\\}");
    private static final String ESCAPED_AT_PLACEHOLDER = "\uE000ESCAPED_AT\uE000";
    private static final int MAX_QR_CONTENT_BYTES = 1000;
    private static final int QR_QUIET_ZONE_MODULES = 4;

    /** Page selection is one-based; original pages retain their boxes, rotation and resources. */
    public void apply(
            PDDocument document,
            List<Integer> pageNumbers,
            String pdfFileName,
            String stampType,
            String stampText,
            BufferedImage stampImage,
            String alphabet,
            float fontSize,
            float rotation,
            float opacity,
            int position,
            float overrideX,
            float overrideY,
            String customColor,
            String customMargin)
            throws IOException {
        if ("qr".equalsIgnoreCase(stampType)) requireEncodableQrContent(stampText);
        float marginFactor =
                switch (customMargin.toLowerCase(Locale.ROOT)) {
                    case "small" -> 0.02f;
                    case "large" -> 0.05f;
                    case "x-large" -> 0.075f;
                    default -> 0.035f;
                };

        PDFont sharedFont =
                "text".equalsIgnoreCase(stampType)
                        ? PdfWatermarks.loadFont(document, alphabet)
                        : null;
        for (int pageIndex : pageNumbers) {
            int zeroBasedIndex = pageIndex - 1;
            if (zeroBasedIndex >= 0 && zeroBasedIndex < document.getNumberOfPages()) {
                PDPage page = document.getPage(zeroBasedIndex);
                PDRectangle pageSize = page.getMediaBox();
                float margin = marginFactor * (pageSize.getWidth() + pageSize.getHeight()) / 2;

                BitMatrix qrMatrix = null;
                if ("qr".equalsIgnoreCase(stampType)) {
                    String qrContent =
                            processStampText(
                                    stampText,
                                    pageIndex,
                                    document.getNumberOfPages(),
                                    pdfFileName,
                                    document);
                    requireEncodableQrContent(qrContent);
                    qrMatrix = encodeQrCode(qrContent);
                }

                PDPageContentStream contentStream =
                        new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true);

                PDExtendedGraphicsState graphicsState = new PDExtendedGraphicsState();
                graphicsState.setNonStrokingAlphaConstant(opacity);
                contentStream.setGraphicsStateParameters(graphicsState);

                if ("text".equalsIgnoreCase(stampType)) {
                    addTextStamp(
                            contentStream,
                            stampText,
                            document,
                            page,
                            rotation,
                            position,
                            fontSize,
                            alphabet,
                            overrideX,
                            overrideY,
                            margin,
                            customColor,
                            pageIndex,
                            pdfFileName,
                            sharedFont);
                } else if ("image".equalsIgnoreCase(stampType)) {
                    addImageStamp(
                            contentStream,
                            stampImage,
                            document,
                            page,
                            rotation,
                            position,
                            fontSize,
                            overrideX,
                            overrideY,
                            margin);
                } else if (qrMatrix != null) {
                    addQrStamp(
                            contentStream,
                            qrMatrix,
                            page,
                            rotation,
                            position,
                            fontSize,
                            overrideX,
                            overrideY,
                            margin);
                }

                contentStream.close();
            }
        }
    }

    private void addTextStamp(
            PDPageContentStream contentStream,
            String stampText,
            PDDocument document,
            PDPage page,
            float rotation,
            int position, // 1-9 positioning logic
            float fontSize,
            String alphabet,
            float overrideX, // X override
            float overrideY, // Y override
            float margin,
            String colorString,
            int currentPageNumber,
            String filename,
            PDFont font)
            throws IOException {
        Color redactColor;
        try {
            if (!colorString.startsWith("#")) {
                colorString = "#" + colorString;
            }
            redactColor = Color.decode(colorString);
        } catch (NumberFormatException e) {
            redactColor = Color.LIGHT_GRAY;
        }

        contentStream.setNonStrokingColor(redactColor);

        int pageCount = document.getNumberOfPages();

        String processedStampText =
                processStampText(stampText, currentPageNumber, pageCount, filename, document);

        String normalizedText =
                Pattern.compile("\\\\n").matcher(processedStampText).replaceAll("\n");
        String[] lines = NEWLINE_PATTERN.split(normalizedText);

        PDRectangle pageSize = page.getMediaBox();

        float effectiveFontSize = fontSize > 0 ? fontSize : 40f;

        contentStream.setFont(font, effectiveFontSize);

        float ascent = font.getFontDescriptor().getAscent();
        float descent = font.getFontDescriptor().getDescent();
        float lineHeight = ((ascent - descent) / 1000) * effectiveFontSize;

        float maxLineWidth = 0;
        for (String line : lines) {
            float lineWidth = calculateTextWidth(line, font, effectiveFontSize);
            if (lineWidth > maxLineWidth) {
                maxLineWidth = lineWidth;
            }
        }

        float totalTextHeight = lines.length * lineHeight;

        float x, y;

        if (overrideX >= 0 && overrideY >= 0) {
            x = overrideX;
            y = overrideY;
        } else {
            x = calculatePositionX(pageSize, position, maxLineWidth, margin);
            y = calculatePositionY(pageSize, position, totalTextHeight, margin);
        }

        contentStream.beginText();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            contentStream.setTextMatrix(
                    Matrix.getRotateInstance(Math.toRadians(rotation), x, y - (i * lineHeight)));
            contentStream.showText(line);
        }
        contentStream.endText();
    }

    /**
     * Process stamp text by replacing all @commands with their actual values. Supported commands:
     *
     * <p>Date & Time:
     *
     * <ul>
     *   <li>@date - Current date (YYYY-MM-DD)
     *   <li>@time - Current time (HH:mm:ss)
     *   <li>@datetime - Current date and time (YYYY-MM-DD HH:mm:ss)
     *   <li>@date{format} - Custom date/time format (e.g., @date{dd/MM/yyyy})
     *   <li>@year - Current year (4 digits)
     *   <li>@month - Current month (01-12)
     *   <li>@day - Current day of month (01-31)
     * </ul>
     *
     * <p>Page Information:
     *
     * <ul>
     *   <li>@page_number or @page - Current page number
     *   <li>@total_pages or @page_count - Total number of pages
     * </ul>
     *
     * <p>File Information:
     *
     * <ul>
     *   <li>@filename - Original filename (without extension)
     *   <li>@filename_full - Original filename (with extension)
     * </ul>
     *
     * <p>Document Metadata:
     *
     * <ul>
     *   <li>@author - Document author (from PDF metadata)
     *   <li>@title - Document title (from PDF metadata)
     *   <li>@subject - Document subject (from PDF metadata)
     * </ul>
     *
     * <p>Other:
     *
     * <ul>
     *   <li>@uuid - Short unique identifier (8 characters)
     * </ul>
     */
    private String processStampText(
            String stampText,
            int currentPageNumber,
            int totalPages,
            String filename,
            PDDocument document) {
        if (stampText == null || stampText.isEmpty()) {
            return "";
        }

        String result = stampText.replace("@@", ESCAPED_AT_PLACEHOLDER);

        LocalDateTime now = LocalDateTime.now();
        String currentDate = now.toLocalDate().toString();
        String currentTime = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String currentDateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String filenameWithoutExt = filename != null ? filename : "";
        if (filename != null && filename.contains(".")) {
            int lastDot = filename.lastIndexOf('.');
            if (lastDot > 0) { // Ensure there's actually a name before the dot
                filenameWithoutExt = filename.substring(0, lastDot);
            }
        }

        String author = "";
        String title = "";
        String subject = "";
        if (document != null && document.getDocumentInformation() != null) {
            var info = document.getDocumentInformation();
            author = info.getAuthor() != null ? info.getAuthor() : "";
            title = info.getTitle() != null ? info.getTitle() : "";
            subject = info.getSubject() != null ? info.getSubject() : "";
        }

        String uuid = UUID.randomUUID().toString().substring(0, 8);

        Matcher matcher = CUSTOM_DATE_PATTERN.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String format = matcher.group(1);
            String replacement = processCustomDateFormat(format, now);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        result = sb.toString();

        result =
                result.replace("@datetime", currentDateTime)
                        .replace("@date", currentDate)
                        .replace("@time", currentTime)
                        .replace("@year", String.valueOf(now.getYear()))
                        .replace("@month", String.format("%02d", now.getMonthValue()))
                        .replace("@day", String.format("%02d", now.getDayOfMonth()))
                        .replace("@page_number", String.valueOf(currentPageNumber))
                        .replace(
                                "@page_count", String.valueOf(totalPages)) // Must come before @page
                        .replace("@total_pages", String.valueOf(totalPages))
                        .replace(
                                "@page",
                                String.valueOf(currentPageNumber)) // Must come after @page_count
                        .replace("@filename_full", filename != null ? filename : "")
                        .replace("@filename", filenameWithoutExt)
                        .replace("@author", author)
                        .replace("@title", title)
                        .replace("@subject", subject)
                        .replace("@uuid", uuid);

        result = result.replace(ESCAPED_AT_PLACEHOLDER, "@");

        return result;
    }

    private String processCustomDateFormat(String format, LocalDateTime now) {
        if (format == null || format.length() > MAX_DATE_FORMAT_LENGTH) {
            return "[invalid format: too long]";
        }

        if (!SAFE_DATE_FORMAT_PATTERN.matcher(format).matches()) {
            return "[invalid format]";
        }

        try {
            return now.format(DateTimeFormatter.ofPattern(format));
        } catch (IllegalArgumentException e) {
            return "[invalid format: " + format + "]";
        }
    }

    private void addImageStamp(
            PDPageContentStream contentStream,
            BufferedImage image,
            PDDocument document,
            PDPage page,
            float rotation,
            int position, // 1-9 positioning logic
            float fontSize,
            float overrideX,
            float overrideY,
            float margin)
            throws IOException {

        float aspectRatio = (float) image.getWidth() / (float) image.getHeight();

        float desiredPhysicalHeight = fontSize;

        float desiredPhysicalWidth = desiredPhysicalHeight * aspectRatio;

        PDImageXObject xobject = LosslessFactory.createFromImage(document, image);

        enterStampBox(
                contentStream,
                page,
                rotation,
                position,
                desiredPhysicalWidth,
                desiredPhysicalHeight,
                overrideX,
                overrideY,
                margin);
        contentStream.drawImage(xobject, 0, 0, desiredPhysicalWidth, desiredPhysicalHeight);
        contentStream.restoreGraphicsState();
    }

    /**
     * Rejects QR content that is blank or over the byte limit. Applied to the raw text and again to
     * each page's expansion, since a placeholder can resolve to nothing (@author with no metadata)
     * or grow the text past the limit (@filename).
     */
    /** Raw and expanded QR text must fit the retained UTF-8 byte limit. */
    public static void validateQr(String content) {
        requireEncodableQrContent(content);
    }

    private static void requireEncodableQrContent(String content) {
        if (content == null || content.isBlank()) {
            throw invalid(
                    "error.stamp.qr.required",
                    "Stamp text must be provided when stamp type is 'qr'");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_QR_CONTENT_BYTES) {
            throw invalid(
                    "error.stamp.qr.tooLong",
                    "QR code content must be at most {0} bytes of UTF-8 text",
                    MAX_QR_CONTENT_BYTES);
        }
    }

    private BitMatrix encodeQrCode(String content) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, QR_QUIET_ZONE_MODULES);
        try {
            return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException e) {
            throw invalid("error.stamp.qr.tooLong", "QR code content is too long to encode");
        }
    }

    /**
     * Draws the QR code as filled vector modules on a white square of {@code size} points, so it
     * stays sharp at any zoom.
     */
    private void addQrStamp(
            PDPageContentStream contentStream,
            BitMatrix matrix,
            PDPage page,
            float rotation,
            int position,
            float size,
            float overrideX,
            float overrideY,
            float margin)
            throws IOException {
        float effectiveSize = size > 0 ? size : 40f;
        enterStampBox(
                contentStream,
                page,
                rotation,
                position,
                effectiveSize,
                effectiveSize,
                overrideX,
                overrideY,
                margin);
        contentStream.setNonStrokingColor(Color.WHITE);
        contentStream.addRect(0, 0, effectiveSize, effectiveSize);
        contentStream.fill();

        int modules = matrix.getWidth();
        float moduleSize = effectiveSize / modules;
        contentStream.setNonStrokingColor(Color.BLACK);
        for (int row = 0; row < modules; row++) {
            float y = effectiveSize - (row + 1) * moduleSize;
            int col = 0;
            while (col < modules) {
                if (!matrix.get(col, row)) {
                    col++;
                    continue;
                }
                int runStart = col;
                while (col < modules && matrix.get(col, row)) {
                    col++;
                }
                contentStream.addRect(
                        runStart * moduleSize, y, (col - runStart) * moduleSize, moduleSize);
            }
        }
        contentStream.fill();
        contentStream.restoreGraphicsState();
    }

    /**
     * Saves the graphics state and maps the box (0,0)-(width,height) onto the stamp's placement on
     * the page, rotated about its lower-left corner. The caller draws inside that box and must then
     * call {@code restoreGraphicsState()}.
     */
    private void enterStampBox(
            PDPageContentStream contentStream,
            PDPage page,
            float rotation,
            int position,
            float desiredPhysicalWidth,
            float desiredPhysicalHeight,
            float overrideX,
            float overrideY,
            float margin)
            throws IOException {
        PDRectangle pageSize = page.getMediaBox();
        float x, y;

        if (overrideX >= 0 && overrideY >= 0) {
            x = overrideX;
            y = overrideY;
        } else {
            x = calculatePositionX(pageSize, position, desiredPhysicalWidth, margin);
            y = calculateImagePositionY(pageSize, position, desiredPhysicalHeight, margin);
        }

        float llx = pageSize.getLowerLeftX();
        float lly = pageSize.getLowerLeftY();
        float urx = pageSize.getUpperRightX();
        float ury = pageSize.getUpperRightY();
        float xMax = Math.max(llx, urx - desiredPhysicalWidth);
        float yMax = Math.max(lly, ury - desiredPhysicalHeight);
        x = Math.min(xMax, Math.max(llx, x));
        y = Math.min(yMax, Math.max(lly, y));

        contentStream.saveGraphicsState();
        contentStream.transform(Matrix.getTranslateInstance(x, y));
        contentStream.transform(Matrix.getRotateInstance(Math.toRadians(rotation), 0, 0));
    }

    private float calculatePositionX(
            PDRectangle pageSize, int position, float contentWidth, float margin) {
        float llx = pageSize.getLowerLeftX();
        float urx = pageSize.getUpperRightX();
        return switch (position % 3) {
            case 1: // Left
                yield llx + margin;
            case 2: // Center
                yield llx + (pageSize.getWidth() - contentWidth) / 2;
            case 0: // Right
                yield urx - contentWidth - margin;
            default:
                yield 0;
        };
    }

    private float calculateImagePositionY(
            PDRectangle pageSize, int position, float imageHeight, float margin) {
        float lly = pageSize.getLowerLeftY();
        float pageHeight = pageSize.getHeight();
        float ury = pageSize.getUpperRightY();
        return switch ((position - 1) / 3) {
            case 0: // Top - upper image edge flush below top margin
                yield ury - margin - imageHeight;
            case 1: // Middle - center image on page
                yield lly + (pageHeight - imageHeight) / 2;
            case 2: // Bottom - lower image edge at bottom margin
                yield lly + margin;
            default:
                yield lly;
        };
    }

    private float calculatePositionY(
            PDRectangle pageSize, int position, float height, float margin) {
        return switch ((position - 1) / 3) {
            case 0: // Top - first line near the top
                yield pageSize.getUpperRightY() - margin;
            case 1: // Middle - center of text block at page center
                yield (pageSize.getHeight() + height) / 2;
            case 2: // Bottom - first line positioned so last line is at bottom margin
                yield pageSize.getLowerLeftY() + margin + height;
            default:
                yield 0;
        };
    }

    private float calculateTextWidth(String text, PDFont font, float fontSize) throws IOException {
        return font.getStringWidth(text) / 1000 * fontSize;
    }

    private static IllegalArgumentException invalid(
            String key, String message, Object... arguments) {
        return new IllegalArgumentException(message);
    }
}

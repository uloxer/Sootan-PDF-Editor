package stirling.software.pdf;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;

import javax.imageio.*;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.*;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.PDFTextStripper;

import stirling.software.pdf.PdfAttachmentFiles.SelectedFile;

/** Local bitmap exports retain PDFBox rendering, TIFF sequences and existing image placement. */
public final class PdfImageConversion {
    private PdfImageConversion() {}

    public static String text(PDDocument document) throws IOException {
        return new PDFTextStripper().getText(document);
    }

    public static void validatePixels(int width, int height) throws IOException {
        if (width < 1 || height < 1 || (long) width * height > 100_000_000L)
            throw new IOException("Image exceeds100million pixels.");
    }

    public static String format(String value) throws IOException {
        String format = value.toLowerCase(Locale.ROOT);
        if (format.equals("jpg")) format = "jpeg";
        if (format.equals("tif")) format = "tiff";
        if (!Set.of("png", "jpeg", "tiff", "bmp", "gif").contains(format)
                || !ImageIO.getImageWritersByFormatName(format).hasNext())
            throw new IOException("Unsupported local bitmap output.");
        return format;
    }

    public static OutputStream bounded(OutputStream target) {
        return new FilterOutputStream(target) {
            long count;

            private void check(int length) throws IOException {
                count += length;
                if (count > 256L * 1024 * 1024)
                    throw new IOException("Bitmap export exceeds256MiB.");
            }

            public void write(int value) throws IOException {
                check(1);
                out.write(value);
            }

            public void write(byte[] bytes, int offset, int length) throws IOException {
                check(length);
                out.write(bytes, offset, length);
            }
        };
    }

    private static BufferedImage renderPage(
            PDFRenderer renderer, PDDocument document, int page, int dpi, ImageType color)
            throws IOException {
        var box = document.getPage(page).getCropBox();
        validatePixels(
                Math.max(1, (int) Math.floor(box.getWidth() * dpi / 72d)),
                Math.max(1, (int) Math.floor(box.getHeight() * dpi / 72d)));
        try {
            return renderer.renderImageWithDPI(page, dpi, color);
        } catch (OutOfMemoryError failure) {
            throw new IOException("Insufficient memory for bitmap export.");
        }
    }

    private static BufferedImage canvas(int width, int height, String format) throws IOException {
        validatePixels(width, height);
        var image =
                new BufferedImage(
                        width,
                        height,
                        format.equals("png")
                                ? BufferedImage.TYPE_INT_ARGB
                                : BufferedImage.TYPE_INT_RGB);
        if (!format.equals("png")) {
            var g = image.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.dispose();
        }
        return image;
    }

    public static byte[] render(
            PDDocument document,
            String requested,
            ImageType color,
            boolean single,
            int dpi,
            String filename,
            boolean annotations)
            throws IOException {
        String format = format(requested);
        if (dpi <= 0 || document.getNumberOfPages() < 1 || document.getNumberOfPages() > 10000)
            throw new IOException("Invalid bitmap export limits.");
        var renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(true);
        if (!annotations) renderer.setAnnotationsFilter(annotation -> false);
        try (var output = new ByteArrayOutputStream()) {
            if (single && format.equals("tiff")) {
                var writer = ImageIO.getImageWritersByFormatName("tiff").next();
                try (var imageOutput = ImageIO.createImageOutputStream(bounded(output))) {
                    writer.setOutput(imageOutput);
                    var params = writer.getDefaultWriteParam();
                    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    params.setCompressionType("ZLib");
                    params.setCompressionQuality(1f);
                    writer.prepareWriteSequence(null);
                    for (int page = 0; page < document.getNumberOfPages(); page++) {
                        var image = renderPage(renderer, document, page, dpi, color);
                        try {
                            writer.writeToSequence(new IIOImage(image, null, null), params);
                        } finally {
                            image.flush();
                        }
                    }
                    writer.endWriteSequence();
                } finally {
                    writer.dispose();
                }
            } else if (single) {
                int width = 0, height = 0;
                for (var page : document.getPages()) {
                    var box = page.getCropBox();
                    int w = Math.max(1, (int) Math.floor(box.getWidth() * dpi / 72d)),
                            h = Math.max(1, (int) Math.floor(box.getHeight() * dpi / 72d));
                    if (page.getRotation() == 90 || page.getRotation() == 270) {
                        int t = w;
                        w = h;
                        h = t;
                    }
                    width = Math.max(width, w);
                    height = Math.addExact(height, h);
                    validatePixels(width, height);
                }
                var combined = canvas(width, height, format);
                var graphics = combined.createGraphics();
                int y = 0;
                try {
                    for (int page = 0; page < document.getNumberOfPages(); page++) {
                        var image = renderPage(renderer, document, page, dpi, color);
                        graphics.drawImage(image, (width - image.getWidth()) / 2, y, null);
                        y += image.getHeight();
                        image.flush();
                    }
                    if (!ImageIO.write(combined, format, output))
                        throw new IOException("No image writer.");
                } finally {
                    graphics.dispose();
                    combined.flush();
                }
            } else {
                String base = io.github.pixee.security.Filenames.toSimpleFileName(filename);
                if (base == null || base.isBlank()) base = "document";
                try (var zip = new ZipOutputStream(bounded(output))) {
                    for (int page = 0; page < document.getNumberOfPages(); page++) {
                        var image = renderPage(renderer, document, page, dpi, color);
                        try {
                            zip.putNextEntry(new ZipEntry(base + "_" + (page + 1) + "." + format));
                            if (!ImageIO.write(image, format, zip))
                                throw new IOException("No image writer.");
                            zip.closeEntry();
                        } finally {
                            image.flush();
                        }
                    }
                }
            }
            return output.toByteArray();
        }
    }

    public static void appendImages(
            PDDocument document, List<SelectedFile> files, String fit, boolean rotate, String color)
            throws IOException {
        PdfAttachmentFiles.validate(files);
        if (!Set.of("fillPage", "maintainAspectRatio", "fitDocumentToImage", "fitDocumentToPage")
                .contains(fit)) throw new IOException("Unsupported page fit.");
        long pixels = 0;
        for (var file : files)
            try (var source = file.open();
                    var input = ImageIO.createImageInputStream(source)) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("Unsupported local bitmap.");
                var reader = readers.next();
                try {
                    String type = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("png", "jpeg", "jpg", "tiff", "tif", "bmp", "gif").contains(type))
                        throw new IOException("Unsupported local bitmap.");
                    reader.setInput(input);
                    int count =
                            type.equals("tiff") || type.equals("tif")
                                    ? reader.getNumImages(true)
                                    : 1;
                    if (count < 1 || count > 1000) throw new IOException("Invalid frame count.");
                    for (int frame = 0; frame < count; frame++) {
                        int width = reader.getWidth(frame), height = reader.getHeight(frame);
                        validatePixels(width, height);
                        pixels += (long) width * height;
                        if (pixels > 100_000_000L)
                            throw new IOException("Combined image pixel limit exceeded.");
                        var original =
                                count == 1 && !type.equals("tiff") && !type.equals("tif")
                                        ? PdfImageInput.loadImageWithExifOrientation(file)
                                        : reader.read(frame);
                        var image = PdfImageInput.convertColorType(original, color);
                        try {
                            var object =
                                    type.equals("jpeg") || type.equals("jpg")
                                            ? JPEGFactory.createFromImage(document, image)
                                            : LosslessFactory.createFromImage(document, image);
                            addImageToDocument(document, object, fit, rotate);
                        } finally {
                            image.flush();
                            if (image != original) original.flush();
                        }
                    }
                } finally {
                    reader.dispose();
                }
            }
    }

    public static void addImageToDocument(
            PDDocument doc, PDImageXObject image, String fitOption, boolean autoRotate)
            throws IOException {
        boolean imageIsLandscape = image.getWidth() > image.getHeight();
        PDRectangle pageSize = PDRectangle.A4;

        if (autoRotate && imageIsLandscape) {
            pageSize = new PDRectangle(pageSize.getHeight(), pageSize.getWidth());
        }

        if ("fitDocumentToImage".equals(fitOption) || "fitDocumentToPage".equals(fitOption)) {
            pageSize = new PDRectangle(image.getWidth(), image.getHeight());
        }

        PDPage page = new PDPage(pageSize);
        doc.addPage(page);

        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        try (PDPageContentStream contentStream =
                new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
            if ("fillPage".equals(fitOption)
                    || "fitDocumentToImage".equals(fitOption)
                    || "fitDocumentToPage".equals(fitOption)) {
                contentStream.drawImage(image, 0, 0, pageWidth, pageHeight);
            } else if ("maintainAspectRatio".equals(fitOption)) {
                float imageAspectRatio = (float) image.getWidth() / (float) image.getHeight();
                float pageAspectRatio = pageWidth / pageHeight;

                float scaleFactor = 1.0f;
                if (imageAspectRatio > pageAspectRatio) {
                    scaleFactor = pageWidth / image.getWidth();
                } else {
                    scaleFactor = pageHeight / image.getHeight();
                }

                float xPos = (pageWidth - (image.getWidth() * scaleFactor)) / 2;
                float yPos = (pageHeight - (image.getHeight() * scaleFactor)) / 2;
                contentStream.drawImage(
                        image,
                        xPos,
                        yPos,
                        image.getWidth() * scaleFactor,
                        image.getHeight() * scaleFactor);
            }
        } catch (IOException e) {

            throw e;
        }
    }
}

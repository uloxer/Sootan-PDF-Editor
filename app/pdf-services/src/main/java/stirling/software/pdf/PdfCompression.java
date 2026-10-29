package stirling.software.pdf;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

/** Shared image compression primitives; helper-based legacy modes remain in their facade. */
@Slf4j
public class PdfCompression {
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    protected static class ImageReference {
        public int pageNum; // Page number where the image appears
        COSName name; // The name used to reference this image
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    @NoArgsConstructor
    protected static class NestedImageReference extends ImageReference {
        COSName formName; // Name of the form XObject containing the image
        COSName imageName; // Name of the image within the form
    }

    // Tracks compression stats for reporting
    protected static class CompressionStats {
        public CompressionStats() {}

        public int totalImages = 0;
        public int nestedImages = 0;
        public int uniqueImagesCount = 0;
        public int compressedImages = 0;
        public int skippedImages = 0;
        public long totalOriginalBytes = 0;
        public long totalCompressedBytes = 0;
    }

    protected static void replaceImages(
            PDDocument doc,
            Map<ImageIdentity, List<ImageReference>> uniqueImages,
            Map<ImageIdentity, PDImageXObject> compressedVersions)
            throws IOException {

        for (Entry<ImageIdentity, List<ImageReference>> entry : uniqueImages.entrySet()) {
            ImageIdentity imageIdentity = entry.getKey();
            List<ImageReference> references = entry.getValue();

            PDImageXObject compressedImage = compressedVersions.get(imageIdentity);
            if (compressedImage == null) continue;

            for (ImageReference ref : references) {
                replaceImageReference(doc, ref, compressedImage);
            }
        }
    }

    // Find all images in the document, both direct and nested within forms
    protected static Map<ImageIdentity, List<ImageReference>> findImages(PDDocument doc)
            throws IOException {
        Map<ImageIdentity, List<ImageReference>> uniqueImages = new HashMap<>();

        // Scan through all pages in the document
        for (int pageNum = 0; pageNum < doc.getNumberOfPages(); pageNum++) {
            PDPage page = doc.getPage(pageNum);
            PDResources res = page.getResources();
            if (res == null || res.getXObjectNames() == null) continue;

            // Process all XObjects on the page
            for (COSName name : res.getXObjectNames()) {
                PDXObject xobj = res.getXObject(name);

                // Direct image
                if (isImage(xobj)) {
                    addDirectImage(pageNum, name, (PDImageXObject) xobj, uniqueImages);
                    log.info(
                            "Found direct image '{}' on page {} - {}x{}",
                            name.getName(),
                            pageNum + 1,
                            ((PDImage) xobj).getWidth(),
                            ((PDImage) xobj).getHeight());
                }
                // Form XObject that may contain nested images
                else if (isForm(xobj)) {
                    checkFormForImages(pageNum, name, (PDFormXObject) xobj, uniqueImages);
                }
            }
        }

        return uniqueImages;
    }

    protected static ImageReference addDirectImage(
            int pageNum,
            COSName name,
            PDImageXObject image,
            Map<ImageIdentity, List<ImageReference>> uniqueImages)
            throws IOException {
        ImageReference ref = new ImageReference();
        ref.pageNum = pageNum;
        ref.name = name;

        ImageIdentity identity = new ImageIdentity(image);
        uniqueImages.computeIfAbsent(identity, k -> new ArrayList<>()).add(ref);

        return ref;
    }

    // Look for images inside form XObjects
    protected static void checkFormForImages(
            int pageNum,
            COSName formName,
            PDFormXObject formXObj,
            Map<ImageIdentity, List<ImageReference>> uniqueImages)
            throws IOException {
        PDResources formResources = formXObj.getResources();
        if (formResources == null || formResources.getXObjectNames() == null) {
            return;
        }

        log.info(
                "Checking form XObject '{}' on page {} for nested images",
                formName.getName(),
                pageNum + 1);

        // Process all XObjects within the form
        for (COSName nestedName : formResources.getXObjectNames()) {
            PDXObject nestedXobj = formResources.getXObject(nestedName);

            if (isImage(nestedXobj)) {
                PDImageXObject nestedImage = (PDImageXObject) nestedXobj;

                log.info(
                        "Found nested image '{}' in form '{}' on page {} - {}x{}",
                        nestedName.getName(),
                        formName.getName(),
                        pageNum + 1,
                        nestedImage.getWidth(),
                        nestedImage.getHeight());

                // Create specialized reference for the nested image
                NestedImageReference nestedRef = new NestedImageReference();
                nestedRef.pageNum = pageNum;
                nestedRef.formName = formName;
                nestedRef.imageName = nestedName;

                ImageIdentity identity = new ImageIdentity(nestedImage);
                uniqueImages.computeIfAbsent(identity, k -> new ArrayList<>()).add(nestedRef);
            }
        }
    }

    // Count total images and nested images
    protected static void calculateImageStats(
            Map<ImageIdentity, List<ImageReference>> uniqueImages, CompressionStats stats) {
        for (List<ImageReference> references : uniqueImages.values()) {
            for (ImageReference ref : references) {
                stats.totalImages++;
                if (ref instanceof NestedImageReference) {
                    stats.nestedImages++;
                }
            }
        }
    }

    protected static boolean isImage(PDXObject xobj) {
        return xobj instanceof PDImageXObject;
    }

    protected static boolean isForm(PDXObject xobj) {
        return xobj instanceof PDFormXObject;
    }

    // Create compressed versions of all unique images
    protected static Map<ImageIdentity, PDImageXObject> createCompressedImages(
            PDDocument doc,
            Map<ImageIdentity, List<ImageReference>> uniqueImages,
            double scaleFactor,
            float jpegQuality,
            boolean convertToGrayscale,
            CompressionStats stats)
            throws IOException {

        Map<ImageIdentity, PDImageXObject> compressedVersions = new HashMap<>();

        // Process each unique image exactly once
        for (Entry<ImageIdentity, List<ImageReference>> entry : uniqueImages.entrySet()) {
            ImageIdentity imageIdentity = entry.getKey();
            List<ImageReference> references = entry.getValue();

            if (references.isEmpty()) continue;

            // Get the first instance of this image
            PDImageXObject originalImage = getOriginalImage(doc, references.getFirst());

            // Track original size
            int originalSize = (int) originalImage.getCOSObject().getLength();
            stats.totalOriginalBytes += originalSize;

            // Process this unique image
            PDImageXObject compressedImage =
                    compressImage(
                            doc,
                            originalImage,
                            originalSize,
                            scaleFactor,
                            jpegQuality,
                            convertToGrayscale);

            if (compressedImage != null) {
                // Store the compressed version in our map
                compressedVersions.put(imageIdentity, compressedImage);
                stats.compressedImages++;

                // Update compression stats
                int compressedSize = (int) compressedImage.getCOSObject().getLength();
                stats.totalCompressedBytes += (long) compressedSize * references.size();

                double reductionPercentage = 100.0 - ((compressedSize * 100.0) / originalSize);
                log.info(
                        "Image identity {}: Compressed from {} to {} (reduced by {}%)",
                        imageIdentity,
                        String.valueOf(originalSize),
                        String.valueOf(compressedSize),
                        String.format(Locale.ROOT, "%.1f", reductionPercentage));
            } else {
                log.info(
                        "Image identity {}: Not suitable for compression, skipping", imageIdentity);
                stats.totalCompressedBytes += (long) originalSize * references.size();
                stats.skippedImages++;
            }
        }

        return compressedVersions;
    }

    protected static String generateImageHash(PDImageXObject image) {
        try {
            try (InputStream stream = image.getCOSObject().createRawInputStream()) {
                byte[] buffer = new byte[16384];
                int bytesRead = stream.read(buffer);
                if (bytesRead > 0) {
                    byte[] dataToHash =
                            bytesRead == buffer.length ? buffer : Arrays.copyOf(buffer, bytesRead);

                    String enhancedData =
                            new String(dataToHash, StandardCharsets.UTF_8)
                                    + "_"
                                    + image.getWidth()
                                    + "_"
                                    + image.getHeight()
                                    + "_"
                                    + image.getColorSpace().getName()
                                    + "_"
                                    + image.getBitsPerComponent();

                    return bytesToHexString(
                            generateMD5(enhancedData.getBytes(StandardCharsets.UTF_8)));
                }
                return "empty-stream";
            }
        } catch (Exception e) {
            log.warn("image hash generation", e);
            return "fallback-" + System.identityHashCode(image);
        }
    }

    protected static String generateMaskHash(PDImageXObject image) {
        try {
            PDImageXObject mask = image.getMask();
            if (mask == null) {
                mask = image.getSoftMask();
            }

            if (mask != null) {
                try (InputStream stream = mask.getCOSObject().createRawInputStream()) {
                    byte[] buffer = new byte[4096];
                    int bytesRead = stream.read(buffer);
                    if (bytesRead > 0) {
                        byte[] dataToHash =
                                bytesRead == buffer.length
                                        ? buffer
                                        : Arrays.copyOf(buffer, bytesRead);
                        return bytesToHexString(generateMD5(dataToHash));
                    }
                    return "empty-mask";
                }
            }
            return "no-mask";
        } catch (Exception e) {
            log.warn("mask hash generation", e);
            return "fallback-mask-" + System.identityHashCode(image);
        }
    }

    // Get original image from a reference
    protected static PDImageXObject getOriginalImage(PDDocument doc, ImageReference ref)
            throws IOException {
        if (ref instanceof NestedImageReference nestedRef) {
            PDPage page = doc.getPage(nestedRef.pageNum);
            PDResources pageResources = page.getResources();

            // Get the form XObject
            PDFormXObject formXObj = (PDFormXObject) pageResources.getXObject(nestedRef.formName);

            // Get the nested image from the form's resources
            PDResources formResources = formXObj.getResources();
            return (PDImageXObject) formResources.getXObject(nestedRef.imageName);
        } else {
            // Get direct image from page resources
            PDPage page = doc.getPage(ref.pageNum);
            PDResources resources = page.getResources();
            return (PDImageXObject) resources.getXObject(ref.name);
        }
    }

    // Try to compress an image if it makes sense
    protected static PDImageXObject compressImage(
            PDDocument doc,
            PDImageXObject originalImage,
            int originalSize,
            double scaleFactor,
            float jpegQuality,
            boolean convertToGrayscale)
            throws IOException {

        // Process and compress the image
        BufferedImage processedImage =
                processAndCompressImage(
                        originalImage, scaleFactor, jpegQuality, convertToGrayscale);

        if (processedImage == null) {
            return null;
        }

        // Convert to bytes for storage
        byte[] compressedData = convertToBytes(processedImage, jpegQuality);

        // Check if compression is beneficial
        if (compressedData.length < originalSize || convertToGrayscale) {
            // Create a compressed version
            return PDImageXObject.createFromByteArray(
                    doc, compressedData, originalImage.getCOSObject().toString());
        }

        return null;
    }

    protected static String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    // Replace a specific image reference with a compressed version
    protected static void replaceImageReference(
            PDDocument doc, ImageReference ref, PDImageXObject compressedImage) throws IOException {
        if (ref instanceof NestedImageReference nestedRef) {
            // Replace nested image within form XObject
            PDPage page = doc.getPage(nestedRef.pageNum);
            PDResources pageResources = page.getResources();

            // Get the form XObject
            PDFormXObject formXObj = (PDFormXObject) pageResources.getXObject(nestedRef.formName);

            // Replace the nested image in the form's resources
            PDResources formResources = formXObj.getResources();
            formResources.put(nestedRef.imageName, compressedImage);

            log.info(
                    "Replaced nested image '{}' in form '{}' on page {} with compressed version",
                    nestedRef.imageName.getName(),
                    nestedRef.formName.getName(),
                    nestedRef.pageNum + 1);
        } else {
            // Replace direct image in page resources
            PDPage page = doc.getPage(ref.pageNum);
            PDResources resources = page.getResources();
            resources.put(ref.name, compressedImage);

            log.info("Replaced direct image on page {} with compressed version", ref.pageNum + 1);
        }
    }

    // Log final stats about the compression
    protected static void logCompressionStats(CompressionStats stats, long originalFileSize) {
        // Calculate image reduction percentage
        double overallImageReduction =
                stats.totalOriginalBytes > 0
                        ? 100.0 - ((stats.totalCompressedBytes * 100.0) / stats.totalOriginalBytes)
                        : 0;

        int duplicatedImages = stats.totalImages - stats.uniqueImagesCount;

        log.info(
                "Image compression summary - Total unique: {}, Compressed: {}, Skipped: {},"
                        + " Duplicates: {}, Nested: {}",
                stats.uniqueImagesCount,
                stats.compressedImages,
                stats.skippedImages,
                duplicatedImages,
                stats.nestedImages);
        log.info(
                "Total original image size: {}, compressed: {} (reduced by {}%)",
                String.valueOf(stats.totalOriginalBytes),
                String.valueOf(stats.totalCompressedBytes),
                String.format(Locale.ROOT, "%.1f", overallImageReduction));
    }

    protected static BufferedImage convertToGrayscale(BufferedImage image) {
        BufferedImage grayImage =
                new BufferedImage(
                        image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);

        Graphics2D g = grayImage.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();

        return grayImage;
    }

    // Resize and optionally convert to grayscale
    protected static BufferedImage processAndCompressImage(
            PDImageXObject image, double scaleFactor, float jpegQuality, boolean convertToGrayscale)
            throws IOException {
        BufferedImage bufferedImage = image.getImage();
        int originalWidth = bufferedImage.getWidth();
        int originalHeight = bufferedImage.getHeight();

        // Minimum dimensions to preserve reasonable quality
        int MIN_WIDTH = 400;
        int MIN_HEIGHT = 400;

        log.info("Original dimensions: {}x{}", originalWidth, originalHeight);

        // Skip if already small enough
        if ((originalWidth <= MIN_WIDTH || originalHeight <= MIN_HEIGHT) && !convertToGrayscale) {
            log.info("Skipping - below minimum dimensions threshold");
            return null;
        }

        // Convert to grayscale first if requested (before resizing for better quality)
        if (convertToGrayscale) {
            bufferedImage = convertToGrayscale(bufferedImage);
            log.info("Converted image to grayscale");
        }

        // Adjust scale factor for very large or very small images
        double adjustedScaleFactor = scaleFactor;
        if (originalWidth > 3000 || originalHeight > 3000) {
            // More aggressive for very large images
            adjustedScaleFactor = Math.min(scaleFactor, 0.75);
            log.info("Very large image, using more aggressive scale: {}", adjustedScaleFactor);
        } else if (originalWidth < 1000 || originalHeight < 1000) {
            // More conservative for smaller images
            adjustedScaleFactor = Math.max(scaleFactor, 0.9);
            log.info("Smaller image, using conservative scale: {}", adjustedScaleFactor);
        }

        int newWidth = (int) (originalWidth * adjustedScaleFactor);
        int newHeight = (int) (originalHeight * adjustedScaleFactor);

        // Ensure minimum dimensions
        newWidth = Math.max(newWidth, MIN_WIDTH);
        newHeight = Math.max(newHeight, MIN_HEIGHT);

        // Skip if change is negligible
        if ((double) newWidth / originalWidth > 0.95
                && (double) newHeight / originalHeight > 0.95
                && !convertToGrayscale) {
            log.info("Change too small, skipping compression");
            return null;
        }

        log.info(
                "Resizing to {}x{} ({}% of original)",
                newWidth, newHeight, Math.round((newWidth * 100.0) / originalWidth));

        BufferedImage scaledImage;
        if (convertToGrayscale) {
            // If already grayscale, maintain the grayscale format
            scaledImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_BYTE_GRAY);
        } else {
            // Otherwise use original color model
            scaledImage =
                    new BufferedImage(
                            newWidth,
                            newHeight,
                            bufferedImage.getColorModel().hasAlpha()
                                    ? BufferedImage.TYPE_INT_ARGB
                                    : BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g2d = scaledImage.createGraphics();
        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(bufferedImage, 0, 0, newWidth, newHeight, null);
        g2d.dispose();

        return scaledImage;
    }

    // Convert image to byte array with quality settings
    protected static byte[] convertToBytes(BufferedImage scaledImage, float jpegQuality)
            throws IOException {
        String format = scaledImage.getColorModel().hasAlpha() ? "png" : "jpeg";
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        if ("jpeg".equals(format)) {
            // Get the best available JPEG writer
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            ImageWriter writer = writers.next();

            JPEGImageWriteParam param = (JPEGImageWriteParam) writer.getDefaultWriteParam();

            // Set compression parameters
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(jpegQuality);
            param.setOptimizeHuffmanTables(true); // Better compression
            param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT); // Progressive scanning

            // Write compressed image
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(outputStream)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(scaledImage, null, null), param);
            }
            writer.dispose();
        } else {
            ImageIO.write(scaledImage, format, outputStream);
        }

        return outputStream.toByteArray();
    }

    // Get image filter/compression type
    protected static String getImageFilter(PDImageXObject image) {
        try {
            return image.getCOSObject().getDictionaryObject(COSName.FILTER).toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // Get color profile information
    protected static String getColorProfileInfo(PDImageXObject image) {
        try {
            // Try to get ICC profile information
            if (image.getColorSpace() != null) {
                return image.getColorSpace().getName()
                        + "_"
                        + image.getColorSpace().getNumberOfComponents();
            }
            return "no-profile";
        } catch (Exception e) {
            return "error-profile";
        }
    }

    // Determine image type from stream data
    protected static String getImageType(PDImageXObject image) {
        try {
            String filter = getImageFilter(image);
            if (filter.contains("DCTDecode") || filter.contains("JPXDecode")) {
                return "JPEG";
            } else if (filter.contains("FlateDecode")) {
                return "PNG";
            } else if (filter.contains("CCITTFaxDecode")) {
                return "TIFF";
            } else if (filter.contains("JBIG2Decode")) {
                return "JBIG2";
            } else {
                return "RAW";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    // Generate hash of decode parameters
    protected static String generateDecodeParamsHash(PDImageXObject image) {
        try {
            // Get decode parameters that affect how the image is processed
            StringBuilder params = new StringBuilder();

            // Add filter information
            params.append(getImageFilter(image));

            // Add color space components
            params.append("_").append(image.getColorSpace().getNumberOfComponents());

            // Add bits per component
            params.append("_").append(image.getBitsPerComponent());

            // Add any decode array parameters
            if (image.getDecode() != null) {
                params.append("_").append(image.getDecode().toString());
            }

            return bytesToHexString(
                    generateMD5(params.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "fallback-decode-" + System.identityHashCode(image);
        }
    }

    protected static byte[] generateMD5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(data); // Get the MD5 hash of the image bytes
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static class ImageIdentity {
        private final String pixelHash; // Hash of pixel data
        private final String colorSpace; // e.g., "DeviceRGB", "DeviceCMYK"
        private final int bitsPerComponent;
        private final boolean hasMask; // Has transparency
        private final String maskHash; // Hash of mask data if present
        private final int width; // Image width in pixels
        private final int height; // Image height in pixels
        private final String filter; // Image filter/compression type
        private final String colorProfile; // Color profile information
        private final long streamLength; // Original stream length
        private final String imageType; // Image type (JPEG, PNG, etc.)
        private final String decodeParams; // Decode parameters hash
        private final String metadataHash; // Hash of image metadata

        public ImageIdentity(PDImageXObject image) throws IOException {
            this.pixelHash = generateImageHash(image);
            this.colorSpace = image.getColorSpace().getName();
            this.bitsPerComponent = image.getBitsPerComponent();
            this.hasMask = image.getMask() != null || image.getSoftMask() != null;
            this.maskHash = hasMask ? generateMaskHash(image) : null;
            this.width = image.getWidth();
            this.height = image.getHeight();
            this.filter = getImageFilter(image);
            this.colorProfile = getColorProfileInfo(image);
            this.streamLength = image.getCOSObject().getLength();
            this.imageType = getImageType(image);
            this.decodeParams = generateDecodeParamsHash(image);
            this.metadataHash = ImageIdentity.generateMetadataHash(image);
        }

        // Generate hash of image metadata
        protected static String generateMetadataHash(PDImageXObject image) {
            try {
                StringBuilder metadata = new StringBuilder();

                // Add image dimensions
                metadata.append(image.getWidth()).append("x").append(image.getHeight());

                // Add color space info
                metadata.append("_").append(image.getColorSpace().getName());

                // Add bits per component
                metadata.append("_").append(image.getBitsPerComponent());

                // Add stream length
                metadata.append("_").append(image.getCOSObject().getLength());

                // Add mask information
                if (image.getMask() != null) {
                    metadata.append("_mask");
                }
                if (image.getSoftMask() != null) {
                    metadata.append("_softmask");
                }

                return bytesToHexString(
                        generateMD5(metadata.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                return "fallback-meta-" + System.identityHashCode(image);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ImageIdentity that)) return false;
            return bitsPerComponent == that.bitsPerComponent
                    && hasMask == that.hasMask
                    && width == that.width
                    && height == that.height
                    && streamLength == that.streamLength
                    && Objects.equals(pixelHash, that.pixelHash)
                    && Objects.equals(colorSpace, that.colorSpace)
                    && Objects.equals(maskHash, that.maskHash)
                    && Objects.equals(filter, that.filter)
                    && Objects.equals(colorProfile, that.colorProfile)
                    && Objects.equals(imageType, that.imageType)
                    && Objects.equals(decodeParams, that.decodeParams)
                    && Objects.equals(metadataHash, that.metadataHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    pixelHash,
                    colorSpace,
                    bitsPerComponent,
                    hasMask,
                    maskHash,
                    width,
                    height,
                    filter,
                    colorProfile,
                    streamLength,
                    imageType,
                    decodeParams,
                    metadataHash);
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "%s_%s_%d_%dx%d_%s_%s_%d_%s_%s_%s",
                    pixelHash.substring(0, Math.min(8, pixelHash.length())),
                    colorSpace,
                    bitsPerComponent,
                    width,
                    height,
                    filter,
                    imageType,
                    streamLength,
                    hasMask ? "masked" : "nomask",
                    decodeParams.substring(0, Math.min(4, decodeParams.length())),
                    metadataHash.substring(0, Math.min(4, metadataHash.length())));
        }
    }

    // Scale factors for different optimization levels (lower => smaller)
    protected static double getScaleFactorForLevel(int optimizeLevel) {
        return switch (optimizeLevel) {
            case 1 -> 0.98; // negligible resizing
            case 2 -> 0.95;
            case 3 -> 0.88;
            case 4 -> 0.78;
            case 5 -> 0.68;
            case 6 -> 0.58;
            case 7 -> 0.48;
            case 8 -> 0.38;
            case 9 -> 0.28;
            default -> 1.0;
        };
    }

    // JPEG quality for different optimization levels (lower => smaller)
    protected static float getJpegQualityForLevel(int optimizeLevel) {
        return switch (optimizeLevel) {
            case 1 -> 0.92f; // very light
            case 2 -> 0.88f;
            case 3 -> 0.85f;
            case 4 -> 0.80f;
            case 5 -> 0.72f;
            case 6 -> 0.65f;
            case 7 -> 0.55f;
            case 8 -> 0.45f;
            case 9 -> 0.35f; // aggressive
            default -> 0.75f;
        };
    }

    // Pick optimization level based on target size
    protected static int determineOptimizeLevel(double sizeReductionRatio) {
        if (sizeReductionRatio > 0.9) return 1;
        if (sizeReductionRatio > 0.8) return 2;
        if (sizeReductionRatio > 0.7) return 3;
        if (sizeReductionRatio > 0.6) return 4;
        if (sizeReductionRatio > 0.3) return 5;
        if (sizeReductionRatio > 0.2) return 6;
        if (sizeReductionRatio > 0.15) return 7;
        if (sizeReductionRatio > 0.1) return 8;
        return 9;
    }

    // Increment optimization level if we need more compression
    protected static int incrementOptimizeLevel(
            int currentLevel, long currentSize, long targetSize) {
        double currentRatio = currentSize / (double) targetSize;
        log.info("Current compression ratio: {}", String.format(Locale.ROOT, "%.2f", currentRatio));

        if (currentRatio > 2.0) {
            return Math.min(9, currentLevel + 3);
        } else if (currentRatio > 1.5) {
            return Math.min(9, currentLevel + 2);
        }
        return Math.min(9, currentLevel + 1);
    }

    /** Applies the existing image-only algorithm to the caller-owned document. */
    public static void images(PDDocument document, double scale, float quality, boolean gray)
            throws IOException {
        PdfProtection.requireOwner(document);
        for (var page : document.getPages()) {
            var resources = page.getResources();
            if (resources != null)
                checkPixels(
                        resources,
                        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()),
                        0);
        }
        var unique = findImages(document);
        var stats = new CompressionStats();
        stats.uniqueImagesCount = unique.size();
        calculateImageStats(unique, stats);
        replaceImages(
                document,
                unique,
                createCompressedImages(document, unique, scale, quality, gray, stats));
    }

    private static void checkPixels(PDResources resources, Set<PDResources> visited, int depth)
            throws IOException {
        if (!visited.add(resources)) return;
        if (depth > 32) throw new IOException("Nested image resources are too deep.");
        for (var name : resources.getXObjectNames()) {
            var object = resources.getXObject(name);
            if (object instanceof PDImageXObject image
                    && (long) image.getWidth() * image.getHeight() > 100_000_000L)
                throw new IOException("Image exceeds the processing pixel limit.");
            if (object instanceof PDFormXObject form && form.getResources() != null)
                checkPixels(form.getResources(), visited, depth + 1);
        }
    }

    /**
     * Keeps the original helper-free level/target-size loop; an unattainable target is best effort.
     */
    public static void compress(
            Path original, PDDocument document, int level, Long target, boolean gray, Path output)
            throws IOException {
        PdfProtection.requireOwner(document);
        if (level < 1 || level > 9 || target != null && target <= 0)
            throw new IllegalArgumentException(
                    "Choose compression level1–9 or a positive target size.");
        if (target != null) level = determineOptimizeLevel(target / (double) Files.size(original));
        Path candidate = output.resolveSibling("compression-candidate.pdf");
        try {
            while (true) {
                if (level >= 4 || gray)
                    images(
                            document,
                            getScaleFactorForLevel(level),
                            getJpegQualityForLevel(level),
                            gray);
                if (level < 4 && !gray)
                    Files.copy(
                            original, candidate, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                else
                    try (var stream =
                            PdfImageConversion.bounded(Files.newOutputStream(candidate))) {
                        document.save(stream);
                    }
                long size = Files.size(candidate);
                if (target == null || size <= target || level == 9) break;
                level = incrementOptimizeLevel(level, size, target);
            }
            Files.copy(
                    Files.size(candidate) < Files.size(original) ? candidate : original,
                    output,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(candidate);
        }
    }
}

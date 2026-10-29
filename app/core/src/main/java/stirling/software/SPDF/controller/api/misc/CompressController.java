package stirling.software.SPDF.controller.api.misc;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.Map.Entry;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.model.api.misc.OptimizePdfRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.LineArtConversionService;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.ProcessExecutor;
import stirling.software.common.util.ProcessExecutor.ProcessExecutorResult;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@MiscApi
@Slf4j
@RequiredArgsConstructor
public class CompressController extends stirling.software.pdf.PdfCompression {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final EndpointConfiguration endpointConfiguration;
    private final TempFileManager tempFileManager;

    @Autowired(required = false)
    private LineArtConversionService lineArtConversionService;

    private boolean isQpdfEnabled() {
        return endpointConfiguration.isGroupEnabled("qpdf");
    }

    private boolean isGhostscriptEnabled() {
        return endpointConfiguration.isGroupEnabled("Ghostscript");
    }

    private boolean isImageMagickEnabled() {
        return endpointConfiguration.isGroupEnabled("ImageMagick");
    }

    public TempFile compressImagesInPDF(
            Path pdfFile, double scaleFactor, float jpegQuality, boolean convertToGrayscale)
            throws Exception {
        TempFile newCompressedPDF = tempFileManager.createManagedTempFile(".pdf");
        long originalFileSize = Files.size(pdfFile);
        log.info(
                "Starting image compression with scale factor: {}, JPEG quality: {}, grayscale: {}"
                        + " on file size: {}",
                scaleFactor,
                jpegQuality,
                convertToGrayscale,
                GeneralUtils.formatBytes(originalFileSize));

        try (PDDocument doc = pdfDocumentFactory.load(pdfFile)) {
            stirling.software.pdf.PdfCompression.images(
                    doc, scaleFactor, jpegQuality, convertToGrayscale);
            doc.save(newCompressedPDF.getAbsolutePath());
            return newCompressedPDF;
        } catch (Exception e) {
            newCompressedPDF.close();
            throw e;
        }
    }

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/compress-pdf",
            resourceWeight = ResourceWeight.LARGE_WEIGHT)
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Optimize PDF file",
            description =
                    "This endpoint accepts a PDF file and optimizes it based on the provided"
                            + " parameters.")
    public ResponseEntity<Resource> optimizePdf(@ModelAttribute OptimizePdfRequest request)
            throws Exception {
        MultipartFile inputFile = request.getFileInput();

        // Validate input file
        if (inputFile == null || inputFile.isEmpty()) {
            throw ExceptionUtils.createFileNullOrEmptyException();
        }

        Integer optimizeLevel = request.getOptimizeLevel();
        String expectedOutputSizeString = request.getExpectedOutputSize();
        Boolean convertToGrayscale = request.getGrayscale();
        Boolean convertToLineArt = request.getLineArt();
        Double lineArtThreshold = request.getLineArtThreshold();
        Integer lineArtEdgeLevel = request.getLineArtEdgeLevel();
        if (expectedOutputSizeString == null && optimizeLevel == null) {
            throw ExceptionUtils.createCompressionOptionsRequiredException();
        }

        Long expectedOutputSize = 0L;
        boolean autoMode = false;
        if (expectedOutputSizeString != null && expectedOutputSizeString.length() > 1) {
            expectedOutputSize = GeneralUtils.convertSizeToBytes(expectedOutputSizeString);
            autoMode = true;
        }

        List<TempFile> tempFiles = new ArrayList<>();

        // Create initial input file
        TempFile originalTempFile = tempFileManager.createManagedTempFile(".pdf");
        tempFiles.add(originalTempFile);
        Path originalFile = originalTempFile.getPath();
        inputFile.transferTo(originalTempFile.getFile());
        long inputFileSize = Files.size(originalFile);

        TempFile currentTempFile = tempFileManager.createManagedTempFile(".pdf");
        tempFiles.add(currentTempFile);
        Path currentFile = currentTempFile.getPath();
        Files.copy(originalFile, currentFile, StandardCopyOption.REPLACE_EXISTING);

        try {
            if (autoMode) {
                double sizeReductionRatio = expectedOutputSize / (double) inputFileSize;
                optimizeLevel = determineOptimizeLevel(sizeReductionRatio);
            }

            if (Boolean.TRUE.equals(convertToLineArt)) {
                if (lineArtConversionService == null) {
                    throw new ResponseStatusException(
                            HttpStatus.FORBIDDEN,
                            "Line art conversion is unavailable - ImageMagick service not found");
                }
                if (!isImageMagickEnabled()) {
                    throw new IOException(
                            "ImageMagick is not enabled but line art conversion was requested");
                }
                double thresholdValue =
                        lineArtThreshold == null
                                ? 55d
                                : Math.min(100d, Math.max(0d, lineArtThreshold));
                int edgeLevel =
                        lineArtEdgeLevel == null ? 1 : Math.min(3, Math.max(1, lineArtEdgeLevel));
                currentFile =
                        applyLineArtConversion(currentFile, tempFiles, thresholdValue, edgeLevel);
            }

            boolean sizeMet = false;
            boolean imageCompressionApplied = false;

            while (!sizeMet && optimizeLevel <= 9) {
                // Apply external compression first
                boolean ghostscriptSuccess = false;

                if (isGhostscriptEnabled() && optimizeLevel >= 6) {
                    try {
                        applyGhostscriptCompression(request, optimizeLevel, currentFile);
                        log.info("Ghostscript compression applied successfully");
                        ghostscriptSuccess = true;
                    } catch (ExceptionUtils.GhostscriptException e) {
                        // Critical Ghostscript errors should be propagated
                        log.error("Ghostscript encountered a critical error: {}", e.getMessage());
                        throw e;
                    } catch (IOException e) {
                        log.warn(
                                "Ghostscript compression failed, continuing with other methods: {}",
                                e.getMessage());
                    }
                }

                // Always apply QPDF when enabled to recompress/optimize structure
                if (isQpdfEnabled()) {
                    try {
                        applyQpdfCompression(request, optimizeLevel, currentFile);
                        log.info("QPDF compression applied successfully");
                    } catch (IOException e) {
                        log.warn("QPDF compression failed: {}", e.getMessage());
                    }
                } else if (!ghostscriptSuccess) {
                    log.info(
                            "No external compression tools available, using image compression"
                                    + " only");
                }

                // Skip image compression if Ghostscript succeeded
                if (ghostscriptSuccess) {
                    imageCompressionApplied = true;
                }

                // Apply image compression for levels 4+ only if Ghostscript didn't run
                if ((optimizeLevel >= 4 || Boolean.TRUE.equals(convertToGrayscale))
                        && !imageCompressionApplied) {
                    // Use different scale factors based on level
                    double scaleFactor = getScaleFactorForLevel(optimizeLevel);
                    // Use JPEG quality settings based on optimization level
                    float jpegQuality = getJpegQualityForLevel(optimizeLevel);

                    log.info(
                            "Applying image compression with scale factor: {} and JPEG quality: {}",
                            scaleFactor,
                            jpegQuality);
                    TempFile compressedImageFile =
                            compressImagesInPDF(
                                    currentFile,
                                    scaleFactor,
                                    jpegQuality,
                                    Boolean.TRUE.equals(convertToGrayscale));

                    tempFiles.add(compressedImageFile);
                    currentFile = compressedImageFile.getPath();
                    imageCompressionApplied = true;
                }

                // Check if target size reached or not in auto mode
                long outputFileSize = Files.size(currentFile);
                if (outputFileSize <= expectedOutputSize || !autoMode) {
                    sizeMet = true;
                } else {
                    int newOptimizeLevel =
                            incrementOptimizeLevel(
                                    optimizeLevel, outputFileSize, expectedOutputSize);

                    // Check if we can't increase the level further
                    if (newOptimizeLevel == optimizeLevel) {
                        log.info("Maximum optimization level reached without meeting target size.");
                        sizeMet = true;
                    } else {
                        // Reset flags for next iteration with higher optimization level
                        imageCompressionApplied = false;
                        optimizeLevel = newOptimizeLevel;
                    }
                }
            }

            if (!Files.exists(currentFile)) {
                log.warn("Optimized file missing or invalid. Using the original file instead.");
                currentFile = originalFile;
            }

            String outputFilename =
                    GeneralUtils.generateFilename(
                            inputFile.getOriginalFilename(), "_Optimized.pdf");

            // Return the upload byte for byte: re-saving it through PDFBox can grow it, and the
            // frontend reports "could not be compressed further" by the size being unchanged.
            if (Files.size(currentFile) >= inputFileSize) {
                log.info("Compression did not reduce the file size. Returning the original file.");
                tempFiles.remove(originalTempFile);
                return WebResponseUtils.pdfFileToWebResponse(originalTempFile, outputFilename);
            }

            try {
                try (PDDocument document = pdfDocumentFactory.load(currentFile.toFile())) {
                    return WebResponseUtils.pdfDocToWebResponse(
                            document, outputFilename, tempFileManager);
                }
            } catch (IOException e) {
                throw ExceptionUtils.handlePdfException(e, "PDF optimization");
            }

        } finally {
            // Clean up all temporary files
            for (TempFile tempFile : tempFiles) {
                try {
                    tempFile.close();
                } catch (Exception e) {
                    log.warn("Failed to delete temporary file: {}", tempFile, e);
                }
            }
        }
    }

    private Path applyLineArtConversion(
            Path currentFile, List<TempFile> tempFiles, double threshold, int edgeLevel)
            throws IOException {

        TempFile lineArtFile = new TempFile(tempFileManager, ".pdf");
        tempFiles.add(lineArtFile);

        try (PDDocument doc = pdfDocumentFactory.load(currentFile.toFile())) {
            Map<ImageIdentity, List<ImageReference>> uniqueImages = findImages(doc);
            CompressionStats stats = new CompressionStats();
            stats.uniqueImagesCount = uniqueImages.size();
            calculateImageStats(uniqueImages, stats);

            Map<ImageIdentity, PDImageXObject> convertedImages =
                    createLineArtImages(doc, uniqueImages, stats, threshold, edgeLevel);

            replaceImages(doc, uniqueImages, convertedImages);

            log.info(
                    "Applied line art conversion to {} unique images ({} total references)",
                    stats.uniqueImagesCount,
                    stats.totalImages);

            doc.save(lineArtFile.getPath().toString());
            return lineArtFile.getPath();
        }
    }

    private Map<ImageIdentity, PDImageXObject> createLineArtImages(
            PDDocument doc,
            Map<ImageIdentity, List<ImageReference>> uniqueImages,
            CompressionStats stats,
            double threshold,
            int edgeLevel)
            throws IOException {

        Map<ImageIdentity, PDImageXObject> convertedImages = new HashMap<>();

        for (Entry<ImageIdentity, List<ImageReference>> entry : uniqueImages.entrySet()) {
            ImageIdentity imageIdentity = entry.getKey();
            List<ImageReference> references = entry.getValue();
            if (references.isEmpty()) continue;

            PDImageXObject originalImage = getOriginalImage(doc, references.getFirst());

            int originalSize = (int) originalImage.getCOSObject().getLength();
            stats.totalOriginalBytes += originalSize;

            PDImageXObject converted =
                    lineArtConversionService.convertImageToLineArt(
                            doc, originalImage, threshold, edgeLevel);
            convertedImages.put(imageIdentity, converted);
            stats.compressedImages++;

            int convertedSize = (int) converted.getCOSObject().getLength();
            stats.totalCompressedBytes += convertedSize * references.size();

            double reductionPercentage = 100.0 - ((convertedSize * 100.0) / originalSize);
            log.info(
                    "Image identity {}: Line art conversion {} → {} (reduced by {}%)",
                    imageIdentity,
                    GeneralUtils.formatBytes(originalSize),
                    GeneralUtils.formatBytes(convertedSize),
                    String.format("%.1f", reductionPercentage));
        }

        return convertedImages;
    }

    // Run Ghostscript compression
    private void applyGhostscriptCompression(
            OptimizePdfRequest request, int optimizeLevel, Path currentFile) throws IOException {

        long preGsSize = Files.size(currentFile);
        log.info("Pre-Ghostscript file size: {}", GeneralUtils.formatBytes(preGsSize));

        try (TempFile gsOutputFile = tempFileManager.createManagedTempFile(".pdf")) {
            Path gsOutputPath = gsOutputFile.getPath();

            // Build Ghostscript command based on optimization level
            List<String> command = new ArrayList<>();
            command.add("gs");
            command.add("-sDEVICE=pdfwrite");
            command.add("-dCompatibilityLevel=1.5");
            command.add("-dNOPAUSE");
            command.add("-dQUIET");
            command.add("-dBATCH");

            // General compression enhancements
            command.add("-dDetectDuplicateImages=true");
            command.add("-dDownsampleColorImages=true");
            command.add("-dCompressFonts=true");
            command.add("-dSubsetFonts=true");

            // Map optimization levels to Ghostscript settings
            switch (optimizeLevel) {
                case 1:
                    command.add("-dPDFSETTINGS=/prepress");
                    break;
                case 2:
                    command.add("-dPDFSETTINGS=/printer");
                    break;
                case 3:
                    command.add("-dPDFSETTINGS=/ebook");
                    break;
                case 4:
                case 5:
                    command.add("-dPDFSETTINGS=/screen");
                    break;
                case 6:
                case 7:
                    command.add("-dPDFSETTINGS=/screen");
                    command.add("-dColorImageResolution=150");
                    command.add("-dGrayImageResolution=150");
                    command.add("-dMonoImageResolution=300");
                    break;
                case 8:
                case 9:
                    command.add("-dPDFSETTINGS=/screen");
                    // Use stronger downsampling at the highest level
                    if (optimizeLevel == 9) {
                        command.add("-dColorImageResolution=72");
                        command.add("-dGrayImageResolution=72");
                        command.add("-dMonoImageResolution=150");
                    } else {
                        command.add("-dColorImageResolution=100");
                        command.add("-dGrayImageResolution=100");
                        command.add("-dMonoImageResolution=200");
                    }
                    break;
                case 10:
                    command.add("-dPDFSETTINGS=/screen");
                    command.add("-dColorImageResolution=72");
                    command.add("-dGrayImageResolution=72");
                    command.add("-dMonoImageResolution=150");
                    break;
                default:
                    command.add("-dPDFSETTINGS=/screen");
                    break;
            }

            // If grayscale conversion requested, enforce grayscale color processing in Ghostscript
            boolean grayscaleRequested = Boolean.TRUE.equals(request.getGrayscale());
            if (grayscaleRequested) {
                command.add("-dColorConversionStrategy=/Gray");
                command.add("-dProcessColorModel=/DeviceGray");
            }

            // Optional conversion: CMYK -> RGB for color output only (avoid conflict with
            // grayscale)
            if (optimizeLevel >= 7 && !grayscaleRequested) {
                command.add("-dConvertCMYKImagesToRGB=true");
            }

            command.add("-sOutputFile=" + gsOutputPath.toString());
            command.add(currentFile.toString());

            ProcessExecutorResult returnCode;
            try {
                returnCode =
                        ProcessExecutor.getInstance(ProcessExecutor.Processes.GHOSTSCRIPT)
                                .runCommandWithOutputHandling(command);

                // Check for critical errors in the output before checking return code
                String gsOutput = returnCode.getMessages();
                ExceptionUtils.GhostscriptException criticalError =
                        ExceptionUtils.detectGhostscriptCriticalError(gsOutput);
                if (criticalError != null) {
                    log.error(
                            "Ghostscript critical error detected: {}", criticalError.getMessage());
                    throw criticalError;
                }

                if (returnCode.getRc() == 0) {
                    // Update current file to the Ghostscript output
                    Files.copy(gsOutputPath, currentFile, StandardCopyOption.REPLACE_EXISTING);

                    long postGsSize = Files.size(currentFile);
                    double gsReduction = 100.0 - ((postGsSize * 100.0) / preGsSize);
                    log.info(
                            "Post-Ghostscript file size: {} (reduced by {}%)",
                            GeneralUtils.formatBytes(postGsSize),
                            String.format(Locale.ROOT, "%.1f", gsReduction));
                } else {
                    log.warn(
                            "Ghostscript compression failed with return code: {}",
                            returnCode.getRc());
                    throw ExceptionUtils.createGhostscriptCompressionException(gsOutput);
                }

            } catch (InterruptedException e) {
                throw ExceptionUtils.createProcessingInterruptedException("Ghostscript", e);
            } catch (ExceptionUtils.GhostscriptException e) {
                // Re-throw Ghostscript-specific exceptions
                throw e;
            } catch (Exception e) {
                log.warn("Ghostscript compression failed, will fallback to other methods", e);
                throw ExceptionUtils.createGhostscriptCompressionException(e);
            }
        }
    }

    // Run QPDF compression
    private void applyQpdfCompression(
            OptimizePdfRequest request, int optimizeLevel, Path currentFile) throws IOException {

        long preQpdfSize = Files.size(currentFile);
        log.info("Pre-QPDF file size: {}", GeneralUtils.formatBytes(preQpdfSize));

        // Map optimization levels to QPDF compression levels
        int qpdfCompressionLevel =
                switch (optimizeLevel) {
                    case 1 -> 3; // faster, lighter
                    case 2 -> 5;
                    case 3, 4, 5 -> 7;
                    default -> 9; // 6-9 use max
                };

        try (TempFile qpdfOutputFile = tempFileManager.createManagedTempFile(".pdf")) {
            Path qpdfOutputPath = qpdfOutputFile.getPath();

            // Build QPDF command
            List<String> command = new ArrayList<>();
            command.add("qpdf");
            if (Boolean.TRUE.equals(request.getNormalize())) {
                command.add("--normalize-content=y");
            }
            if (Boolean.TRUE.equals(request.getLinearize())) {
                command.add("--linearize");
            }
            // Decode/encode settings for maximal recompression
            command.add("--decode-level=generalized");
            command.add("--recompress-flate");
            command.add("--compression-level=" + qpdfCompressionLevel);
            command.add("--compress-streams=y");
            command.add("--stream-data=compress");
            // Preserve unreferenced only at lower levels for safety; skip at highest levels for
            // size
            if (optimizeLevel <= 3) {
                command.add("--preserve-unreferenced");
            }
            // Optional image optimization in qpdf (no resampling; lossy JPEG when beneficial)
            // Enable qpdf image optimization at medium/high levels
            if (optimizeLevel >= 5) {
                command.add("--optimize-images");
                // Map optimize level to JPEG quality (lower number => smaller size)
                Integer jpegQuality =
                        switch (optimizeLevel) {
                            case 5 -> 78;
                            case 6 -> 68;
                            case 7 -> 58;
                            case 8 -> 46;
                            default -> 34; // 9+
                        };
                command.add("--jpeg-quality=" + jpegQuality);
            }
            command.add("--object-streams=generate");
            command.add(currentFile.toString());
            command.add(qpdfOutputPath.toString());

            ProcessExecutorResult returnCode = null;
            try {
                // On high levels, prefer zopfli if platform supports env wrapper
                if (optimizeLevel >= 8) {
                    String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
                    if (!os.contains("win")) {
                        // Prepend env QPDF_ZOPFLI=silent for Unix-like systems
                        List<String> zopfliCommand = new ArrayList<>();
                        zopfliCommand.add("env");
                        zopfliCommand.add("QPDF_ZOPFLI=silent");
                        zopfliCommand.addAll(command);
                        command = zopfliCommand;
                    }
                }
                returnCode =
                        ProcessExecutor.getInstance(ProcessExecutor.Processes.QPDF)
                                .runCommandWithOutputHandling(command, null);

                long postQpdfSize = Files.size(qpdfOutputPath);
                if (postQpdfSize >= preQpdfSize) {
                    log.info(
                            "QPDF output ({}) is not smaller than its input, keeping the input",
                            GeneralUtils.formatBytes(postQpdfSize));
                    return;
                }
                Files.copy(qpdfOutputPath, currentFile, StandardCopyOption.REPLACE_EXISTING);

                double qpdfReduction = 100.0 - ((postQpdfSize * 100.0) / preQpdfSize);
                log.info(
                        "Post-QPDF file size: {} (reduced by {}%)",
                        GeneralUtils.formatBytes(postQpdfSize),
                        String.format(Locale.ROOT, "%.1f", qpdfReduction));

            } catch (IOException e) {
                if (returnCode != null && returnCode.getRc() != 3) {
                    throw ExceptionUtils.createQpdfCompressionException(e);
                }
                // If QPDF fails, keep using the current file
                log.warn("QPDF compression failed, continuing with current file", e);
            } catch (InterruptedException e) {
                throw ExceptionUtils.createProcessingInterruptedException("QPDF", e);
            }
        }
    }
}

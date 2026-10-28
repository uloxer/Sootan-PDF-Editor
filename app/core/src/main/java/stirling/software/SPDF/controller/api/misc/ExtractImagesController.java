package stirling.software.SPDF.controller.api.misc;

import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.swagger.MultiFileResponse;
import stirling.software.SPDF.model.api.PDFExtractImagesRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolArity;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@MiscApi
@Slf4j
@RequiredArgsConstructor
public class ExtractImagesController extends stirling.software.pdf.PdfImageExtraction {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/extract-images",
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @MultiFileResponse
    @ToolIO(produces = ToolFormat.IMAGE, arity = ToolArity.SIMO)
    @Operation(
            summary = "Extract images from a PDF file",
            description =
                    "This endpoint extracts images from a given PDF file and returns them in a zip"
                            + " file. Users can specify the output image format.")
    public ResponseEntity<Resource> extractImages(@ModelAttribute PDFExtractImagesRequest request)
            throws IOException {
        MultipartFile file = request.getFileInput();
        String imageFormat = request.getFormat();

        String baseFilename = GeneralUtils.removeExtension(file.getOriginalFilename());
        ImageFingerprints fingerprints = new ImageFingerprints();

        TempFile zipFile = new TempFile(tempFileManager, ".zip");
        try (ZipOutputStream zipStream =
                        new ZipOutputStream(Files.newOutputStream(zipFile.getPath()));
                PDDocument pdfDoc = pdfDocumentFactory.load(file)) {

            zipStream.setLevel(Deflater.BEST_COMPRESSION);

            int totalPages = pdfDoc.getNumberOfPages();
            for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
                PDPage currentPage = pdfDoc.getPage(pageIndex);
                extractAndAddImagesToZip(
                        currentPage,
                        imageFormat,
                        baseFilename,
                        pageIndex + 1,
                        fingerprints,
                        zipStream);
            }
        } catch (Exception e) {
            zipFile.close();
            throw e;
        }

        return WebResponseUtils.zipFileToWebResponse(
                zipFile, baseFilename + "_extracted-images.zip");
    }
}

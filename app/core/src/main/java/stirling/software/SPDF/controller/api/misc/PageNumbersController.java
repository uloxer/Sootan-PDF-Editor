package stirling.software.SPDF.controller.api.misc;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.misc.AddPageNumbersRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;
import stirling.software.pdf.PdfPageNumbers;

@MiscApi
@RequiredArgsConstructor
public class PageNumbersController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            value = "/add-page-numbers",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @StandardPdfResponse
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Add page numbers to a PDF document",
            description = "This operation takes an input PDF file and adds page numbers to it.")
    public ResponseEntity<Resource> addPageNumbers(@ModelAttribute AddPageNumbersRequest request)
            throws IOException {

        MultipartFile file = request.getFileInput();
        String customMargin = request.getCustomMargin();
        int position = request.getPosition();
        int pageNumber = request.getStartingNumber();
        String pagesToNumber = request.getPagesToNumber();
        String customText = request.getCustomText();
        int zeroPad = request.getZeroPad();
        float fontSize = request.getFontSize();
        String fontType = request.getFontType();
        String fontColor = request.getFontColor();
        try (PDDocument document = pdfDocumentFactory.load(file)) {
            PdfPageNumbers.apply(
                    document,
                    GeneralUtils.parsePageList(
                            (pagesToNumber == null || pagesToNumber.isEmpty()
                                            ? "all"
                                            : pagesToNumber)
                                    .split(","),
                            document.getNumberOfPages()),
                    customMargin,
                    position,
                    pageNumber,
                    customText,
                    zeroPad,
                    fontSize,
                    fontType,
                    fontColor,
                    GeneralUtils.removeExtension(
                            Filenames.toSimpleFileName(file.getOriginalFilename())));
            TempFile tempOut = tempFileManager.createManagedTempFile(".pdf");
            try {
                document.save(tempOut.getFile());
            } catch (IOException e) {
                tempOut.close();
                throw e;
            }

            return WebResponseUtils.pdfFileToWebResponse(
                    tempOut,
                    GeneralUtils.generateFilename(
                            file.getOriginalFilename(), "_page_numbers_added.pdf"));
        }
    }
}

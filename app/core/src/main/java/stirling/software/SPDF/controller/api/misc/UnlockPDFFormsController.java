package stirling.software.SPDF.controller.api.misc;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;
import stirling.software.pdf.PdfForms;

@MiscApi
@Slf4j
public class UnlockPDFFormsController {
    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    public UnlockPDFFormsController(
            CustomPDFDocumentFactory pdfDocumentFactory, TempFileManager tempFileManager) {
        this.pdfDocumentFactory = pdfDocumentFactory;
        this.tempFileManager = tempFileManager;
    }

    @AutoJobPostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            value = "/unlock-pdf-forms",
            resourceWeight = ResourceWeight.SMALL_WEIGHT)
    @StandardPdfResponse
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Remove read-only property from form fields",
            description = "Removing read-only property from form fields making them fillable")
    public ResponseEntity<Resource> unlockPDFForms(@ModelAttribute PDFFile file) {
        try (PDDocument document = pdfDocumentFactory.load(file)) {
            PdfForms.unlock(document);
            String mergedFileName =
                    GeneralUtils.generateFilename(
                            file.getFileInput().getOriginalFilename(), "_unlocked_forms.pdf");
            return WebResponseUtils.pdfDocToWebResponse(
                    document, Filenames.toSimpleFileName(mergedFileName), tempFileManager);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }
}

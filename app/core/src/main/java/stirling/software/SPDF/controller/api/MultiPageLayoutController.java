package stirling.software.SPDF.controller.api;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.general.MergeMultiplePagesRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.GeneralApi;
import stirling.software.common.enumeration.ResourceWeight;
import stirling.software.common.model.tool.ToolFormat;
import stirling.software.common.model.tool.ToolIO;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@GeneralApi
@RequiredArgsConstructor
@Slf4j
public class MultiPageLayoutController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(
            value = "/multi-page-layout",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            resourceWeight = ResourceWeight.MEDIUM_WEIGHT)
    @ToolIO(produces = ToolFormat.PDF)
    @Operation(
            summary = "Merge multiple pages of a PDF document into a single page",
            description =
                    "This operation takes an input PDF file and the number of pages to merge into a"
                            + " single sheet in the output PDF file.")
    public ResponseEntity<Resource> mergeMultiplePagesIntoOne(
            @ModelAttribute MergeMultiplePagesRequest request) throws IOException {

        var options = new stirling.software.pdf.PdfNUp.Options();
        options.setMode(request.getMode());
        options.setPagesPerSheet(request.getPagesPerSheet());
        options.setRows(request.getRows());
        options.setCols(request.getCols());
        options.setOrientation(request.getOrientation());
        options.setArrangement(request.getArrangement());
        options.setReadingDirection(request.getReadingDirection());
        options.setInnerMargin(request.getInnerMargin());
        options.setTopMargin(request.getTopMargin());
        options.setBottomMargin(request.getBottomMargin());
        options.setLeftMargin(request.getLeftMargin());
        options.setRightMargin(request.getRightMargin());
        options.setBorderWidth(request.getBorderWidth());
        options.setAddBorder(request.getAddBorder());
        try (PDDocument sourceDocument = pdfDocumentFactory.load(request.getFileInput());
                PDDocument newDocument =
                        pdfDocumentFactory.createNewDocumentBasedOnOldDocument(sourceDocument)) {
            stirling.software.pdf.PdfNUp.apply(sourceDocument, newDocument, options);
            return WebResponseUtils.pdfDocToWebResponse(
                    newDocument,
                    GeneralUtils.generateFilename(
                            request.getFileInput().getOriginalFilename(), "_multi_page_layout.pdf"),
                    tempFileManager);
        }
    }
}

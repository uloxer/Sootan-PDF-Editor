package stirling.software.SPDF.controller.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import stirling.software.common.annotations.api.GeneralApi;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.pdf.PdfCharcodes;

/** Legacy transport for the shared local font encoding algorithm. */
@GeneralApi
public class PdfTextEditorCharcodeController extends PdfCharcodes {
    public static class EncodeCharcodesRequest extends PdfCharcodes.EncodeCharcodesRequest {}

    public static class EncodeCharcodesResponse extends PdfCharcodes.EncodeCharcodesResponse {}

    public PdfTextEditorCharcodeController(CustomPDFDocumentFactory factory) {
        super(bytes -> factory.load(bytes, true));
    }

    @PostMapping(
            value = "/pdf-text-editor/encode-charcodes",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<EncodeCharcodesResponse> encodeCharcodes(
            @RequestBody EncodeCharcodesRequest request) {
        Result result = encode(request);
        EncodeCharcodesResponse response = new EncodeCharcodesResponse();
        response.setCharcodes(result.body().getCharcodes());
        response.setMissing(result.body().getMissing());
        response.setNote(result.body().getNote());
        response.setError(result.body().getError());
        return ResponseEntity.status(result.status()).body(response);
    }
}

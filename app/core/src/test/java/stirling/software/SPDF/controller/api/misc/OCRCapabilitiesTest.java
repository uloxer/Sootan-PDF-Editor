package stirling.software.SPDF.controller.api.misc;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.config.ExternalAppDepConfig;
import stirling.software.SPDF.model.api.misc.ProcessPdfWithOcrRequest;
import stirling.software.common.configuration.RuntimePathConfig;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.TempFileManager;

class OCRCapabilitiesTest {
    private final ExternalAppDepConfig helpers = mock(ExternalAppDepConfig.class);
    private final EndpointConfiguration endpoints = mock(EndpointConfiguration.class);
    private final OCRController controller =
            new OCRController(
                    new ApplicationProperties(),
                    mock(CustomPDFDocumentFactory.class),
                    mock(TempFileManager.class),
                    endpoints,
                    mock(RuntimePathConfig.class),
                    helpers);

    private ProcessPdfWithOcrRequest request() {
        var request = new ProcessPdfWithOcrRequest();
        request.setLanguages(List.of("eng"));
        request.setOcrRenderType("auto");
        return request;
    }

    @Test
    void unavailableCleaningIsRejectedBeforeProcessing() {
        for (boolean finalOutput : List.of(false, true)) {
            var request = request();
            request.setClean(!finalOutput);
            request.setCleanFinal(finalOutput);
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> controller.processPdfWithOCR(request));
            assertTrue(error.getMessage().contains("unpaper"));
        }
    }

    @Test
    void unavailableImageRemovalIsRejectedBeforeProcessing() {
        var request = request();
        request.setRemoveImagesAfter(true);
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.processPdfWithOCR(request));
        assertTrue(error.getMessage().contains("Ghostscript"));
    }

    @Test
    void tesseractOnlyDoesNotSilentlyIgnoreSidecar() {
        when(endpoints.isGroupEnabled("OCRmyPDF")).thenReturn(false);
        var request = request();
        request.setSidecar(true);
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> controller.processPdfWithOCR(request));
        assertTrue(error.getMessage().contains("OCRmyPDF"));
    }
}

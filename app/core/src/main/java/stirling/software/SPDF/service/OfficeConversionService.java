package stirling.software.SPDF.service;

import java.time.Duration;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.officeconvert.OfficeConvert;

/**
 * PDF to Office in process through Stirling Office Convert, and the switch between it and the
 * legacy converters. Conversions are stopped at the LibreOffice timeout.
 */
@Service
@RequiredArgsConstructor
public class OfficeConversionService extends stirling.software.pdf.PdfOfficeConversion {

    /** Output formats by extension; a .doc is RTF, which Word opens as a document. */
    private final ApplicationProperties applicationProperties;

    private final EndpointConfiguration endpointConfiguration;

    /** Whether the legacy converters (LibreOffice, Tabula, plain text) apply. */
    public boolean legacy() {
        return legacy(null);
    }

    /** {@code choice} is the caller's per-request pick; null follows the admin setting. */
    public boolean legacy(Boolean choice) {
        return choice != null
                ? !choice
                : !applicationProperties.getSystem().isStirlingOfficeConversion();
    }

    public boolean libreOfficeAvailable() {
        return endpointConfiguration.isGroupEnabled("LibreOffice");
    }

    /** Stirling Office Convert replaces LibreOffice only when the admin enables it. */
    public boolean replacesLibreOffice() {
        return !legacy();
    }

    public boolean replacesLibreOffice(Boolean choice) {
        return !legacy(choice);
    }

    public OfficeConvert.Settings settings() {
        long minutes =
                applicationProperties
                        .getProcessExecutor()
                        .getTimeoutMinutes()
                        .getLibreOfficeTimeoutMinutes();
        return OfficeConvert.Settings.defaults().timeout(Duration.ofMinutes(minutes));
    }
}

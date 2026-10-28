package stirling.software.common.util;

import org.springframework.stereotype.Component;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.SsrfProtectionService;

@Component
public class SvgSanitizer extends stirling.software.pdf.SvgContentSanitizer {
    public SvgSanitizer(SsrfProtectionService ssrf, ApplicationProperties properties) {
        super(
                url -> ssrf == null || ssrf.isUrlAllowed(url),
                () -> properties.getSystem().isDisableSanitize());
    }
}

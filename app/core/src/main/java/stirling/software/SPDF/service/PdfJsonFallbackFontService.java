package stirling.software.SPDF.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import stirling.software.common.model.ApplicationProperties;
import stirling.software.pdf.json.PdfJsonContext;

@Component
public class PdfJsonFallbackFontService extends stirling.software.pdf.json.PdfJsonFallbackFonts {
    public PdfJsonFallbackFontService(ResourceLoader loader, ApplicationProperties properties) {
        super(
                location ->
                        new PdfJsonContext.Resource() {
                            private final org.springframework.core.io.Resource resource =
                                    loader.getResource(location);

                            public boolean exists() {
                                return resource.exists();
                            }

                            public java.io.InputStream getInputStream() throws java.io.IOException {
                                return resource.getInputStream();
                            }
                        },
                () ->
                        properties.getPdfEditor() == null
                                ? null
                                : properties.getPdfEditor().getFallbackFont());
    }

    @Value("${stirling.pdf.fallback-font:" + DEFAULT_FALLBACK_FONT_LOCATION + "}")
    public void legacyLocation(String value) {
        legacyFallbackFontLocation = value;
    }

    @jakarta.annotation.PostConstruct
    protected void loadConfig() {
        super.loadConfig();
    }
}

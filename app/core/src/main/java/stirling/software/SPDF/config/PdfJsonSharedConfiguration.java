package stirling.software.SPDF.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PdfJsonSharedConfiguration {
    @Bean
    stirling.software.SPDF.service.PdfJsonCosMapper pdfJsonCosMapper() {
        return new stirling.software.SPDF.service.PdfJsonCosMapper();
    }

    @Bean
    stirling.software.SPDF.service.pdfjson.type3.Type3GlyphExtractor type3GlyphExtractor() {
        return new stirling.software.SPDF.service.pdfjson.type3.Type3GlyphExtractor();
    }
}

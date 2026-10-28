package stirling.software.SPDF.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Collections;

import javax.security.auth.x500.X500Principal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.junit.jupiter.api.Test;

import stirling.software.pdf.PdfVisibleSignature;

class SharedSignatureResourceTest {
    @Test
    void singleSharedLogoBuildsReloadableVisibleAppearance() throws Exception {
        var resources =
                Collections.list(
                        PdfVisibleSignature.class
                                .getClassLoader()
                                .getResources("static/images/signature.png"));
        assertEquals(1, resources.size());
        var certificate = mock(X509Certificate.class);
        when(certificate.getSubjectX500Principal())
                .thenReturn(new X500Principal("CN=Local signing fixture"));
        var signature = new PDSignature();
        signature.setSignDate(Calendar.getInstance());
        signature.setReason("Local fixture");
        try (var source = new PDDocument()) {
            source.addPage(new PDPage());
            try (var appearance =
                            PdfVisibleSignature.create(source, signature, 0, true, certificate);
                    var reloaded = Loader.loadPDF(appearance.readAllBytes())) {
                assertEquals(1, reloaded.getNumberOfPages());
                var widget =
                        reloaded.getDocumentCatalog()
                                .getAcroForm()
                                .getFields()
                                .getFirst()
                                .getWidgets()
                                .getFirst();
                assertTrue(
                        widget.getNormalAppearanceStream()
                                .getResources()
                                .getXObjectNames()
                                .iterator()
                                .hasNext());
            }
        }
    }
}

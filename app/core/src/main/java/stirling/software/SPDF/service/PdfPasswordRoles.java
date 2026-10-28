package stirling.software.SPDF.service;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;

/** HTTP compatibility facade for shared plain-PDFBox password behavior. */
public final class PdfPasswordRoles {
    private PdfPasswordRoles() {}

    public static boolean isOpeningPassword(PDDocument document, String password)
            throws IOException {
        return stirling.software.pdf.PdfPasswordRoles.isOpeningPassword(document, password);
    }

    public static boolean hasSeparateOwnerPassword(PDDocument document, String password)
            throws IOException {
        return stirling.software.pdf.PdfPasswordRoles.hasSeparateOwnerPassword(document, password);
    }
}

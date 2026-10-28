package stirling.software.SPDF.service;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;

/** HTTP compatibility facade for shared plain-PDFBox password behavior. */
public final class PdfPermissionSecurityHandler {
    private PdfPermissionSecurityHandler() {}

    public static void changePermissions(
            PDDocument document, String password, AccessPermission permissions) throws IOException {
        stirling.software.pdf.PdfPermissionSecurityHandler.changePermissions(
                document, password, permissions);
    }
}

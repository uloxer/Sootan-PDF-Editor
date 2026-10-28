package stirling.software.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

/** Shared form mutations. The caller owns document permission and signature checks. */
public final class PdfForms {
    private static final Pattern ACCESS_READ_ONLY = Pattern.compile("access\\s*=\\s*\"readOnly\"");

    private PdfForms() {}

    /** Clears read-only and lock flags, including readOnly access attributes in XFA packets. */
    public static void unlock(PDDocument document) throws IOException {
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();

        if (acroForm != null) {
            acroForm.setNeedAppearances(true);

            for (PDField field : acroForm.getFieldTree()) {
                COSDictionary dict = field.getCOSObject();
                if (dict.containsKey(COSName.getPDFName("Lock"))) {
                    dict.removeItem(COSName.getPDFName("Lock"));
                }
                int currentFlags = field.getFieldFlags();
                if ((currentFlags & 1) == 1) {
                    int newFlags = currentFlags & ~1;
                    field.setFieldFlags(newFlags);
                }
            }

            COSBase xfaBase = acroForm.getCOSObject().getDictionaryObject(COSName.XFA);
            if (xfaBase != null) {
                try {
                    var accessReadOnlyPattern = ACCESS_READ_ONLY;
                    if (xfaBase instanceof COSStream xfaStream) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (InputStream is = xfaStream.createInputStream()) {
                            is.transferTo(baos);
                        }
                        String xml = baos.toString(StandardCharsets.UTF_8);

                        xml = accessReadOnlyPattern.matcher(xml).replaceAll("access=\"open\"");

                        PDStream newStream =
                                new PDStream(
                                        document,
                                        new ByteArrayInputStream(
                                                xml.getBytes(StandardCharsets.UTF_8)));
                        acroForm.getCOSObject().setItem(COSName.XFA, newStream.getCOSObject());
                    } else if (xfaBase instanceof COSArray xfaArray) {
                        for (int i = 0; i < xfaArray.size(); i += 2) {
                            COSBase namePart = xfaArray.getObject(i);
                            COSBase streamPart = xfaArray.getObject(i + 1);
                            if (namePart instanceof COSString
                                    && streamPart instanceof COSStream stream) {
                                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                try (InputStream is = stream.createInputStream()) {
                                    is.transferTo(baos);
                                }
                                String xml = baos.toString(StandardCharsets.UTF_8);

                                xml =
                                        accessReadOnlyPattern
                                                .matcher(xml)
                                                .replaceAll("access=\"open\"");

                                PDStream newStream =
                                        new PDStream(
                                                document,
                                                new ByteArrayInputStream(
                                                        xml.getBytes(StandardCharsets.UTF_8)));
                                xfaArray.set(i + 1, newStream.getCOSObject());
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new java.io.IOException("The XFA form could not be unlocked.", e);
                }
            }
        }
    }

    /** Paints form appearances into pages without rasterizing page text or vector content. */
    public static void flatten(PDDocument document) throws IOException {
        PDAcroForm form = document.getDocumentCatalog().getAcroForm();
        if (form != null) form.flatten();
    }
}

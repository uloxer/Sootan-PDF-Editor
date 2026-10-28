package stirling.software.pdf;

import java.awt.*;
import java.io.*;
import java.security.cert.X509Certificate;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.util.Matrix;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;

/** Local visible appearance derived from the retained PDFBox signing template. */
public final class PdfVisibleSignature {
    private PdfVisibleSignature() {}

    /** Caller supplies a validated zero-based page index and owns the returned stream. */
    public static InputStream create(
            PDDocument srcDoc,
            PDSignature signature,
            Integer pageNumber,
            Boolean showLogo,
            X509Certificate certificate)
            throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(srcDoc.getPage(pageNumber).getMediaBox());
            page.setCropBox(srcDoc.getPage(pageNumber).getCropBox());
            page.setRotation(srcDoc.getPage(pageNumber).getRotation());
            doc.addPage(page);
            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDSignatureField signatureField = new PDSignatureField(acroForm);
            PDAnnotationWidget widget = signatureField.getWidgets().getFirst();
            List<PDField> acroFormFields = acroForm.getFields();
            acroForm.setSignaturesExist(true);
            acroForm.setAppendOnly(true);
            acroForm.getCOSObject().setDirect(true);
            acroFormFields.add(signatureField);

            PDRectangle rect =
                    new PDRectangle(
                            page.getCropBox().getLowerLeftX(),
                            page.getCropBox().getLowerLeftY(),
                            200,
                            50);

            widget.setRectangle(rect);

            PDStream stream = new PDStream(doc);
            PDFormXObject form = new PDFormXObject(stream);
            PDResources res = new PDResources();
            form.setResources(res);
            form.setFormType(1);
            PDRectangle bbox = new PDRectangle(rect.getWidth(), rect.getHeight());
            float height = bbox.getHeight();
            form.setBBox(bbox);
            PDFont font = new PDType1Font(FontName.TIMES_BOLD);

            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            appearance.getCOSObject().setDirect(true);
            PDAppearanceStream appearanceStream = new PDAppearanceStream(form.getCOSObject());
            appearance.setNormalAppearance(appearanceStream);
            widget.setAppearance(appearance);

            try (PDPageContentStream cs = new PDPageContentStream(doc, appearanceStream)) {
                if (Boolean.TRUE.equals(showLogo)) {
                    cs.saveGraphicsState();
                    PDExtendedGraphicsState extState = new PDExtendedGraphicsState();
                    extState.setBlendMode(BlendMode.MULTIPLY);
                    extState.setNonStrokingAlphaConstant(0.5f);
                    cs.setGraphicsStateParameters(extState);
                    cs.transform(Matrix.getScaleInstance(0.08f, 0.08f));
                    PDImageXObject img =
                            PDImageXObject.createFromByteArray(
                                    doc,
                                    PdfVisibleSignature.class
                                            .getResourceAsStream("/signature.png")
                                            .readAllBytes(),
                                    "signature.png");
                    cs.drawImage(img, 100, 0);
                    cs.restoreGraphicsState();
                }

                float fontSize = 10;
                float leading = fontSize * 1.5f;
                cs.beginText();
                cs.setFont(font, fontSize);
                cs.setNonStrokingColor(Color.black);
                cs.newLineAtOffset(fontSize, height - leading);
                cs.setLeading(leading);

                X509Certificate cert = certificate;

                X500Name x500Name = new X500Name(cert.getSubjectX500Principal().getName());
                RDN cn = x500Name.getRDNs(BCStyle.CN)[0];
                String name = IETFUtils.valueToString(cn.getFirst().getValue());

                String date = signature.getSignDate().getTime().toString();
                String reason = signature.getReason();

                cs.showText("Signed by " + name);
                cs.newLine();
                cs.showText(date);
                cs.newLine();
                cs.showText(reason);

                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return new ByteArrayInputStream(baos.toByteArray());
        }
    }
}

package stirling.software.pdf;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.action.PDDocumentCatalogAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.action.PDFormFieldAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.action.PDPageAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

/**
 * Shared document mutations; callers retain document ownership and choose explicit removal flags.
 */
public final class PdfSanitizer {
    private PdfSanitizer() {}

    public static void sanitizeJavaScript(PDDocument document) throws IOException {
        // Get the root dictionary (catalog) of the PDF
        PDDocumentCatalog catalog = document.getDocumentCatalog();

        // Get the Names dictionary
        COSDictionary namesDict =
                (COSDictionary) catalog.getCOSObject().getDictionaryObject(COSName.NAMES);

        if (namesDict != null) {
            // Get the JavaScript dictionary
            COSDictionary javaScriptDict =
                    (COSDictionary) namesDict.getDictionaryObject(COSName.getPDFName("JavaScript"));

            if (javaScriptDict != null) {
                // Remove the JavaScript dictionary
                namesDict.removeItem(COSName.getPDFName("JavaScript"));
            }
        }

        if (catalog.getOpenAction() instanceof PDActionJavaScript) {
            catalog.setOpenAction(null);
        }

        PDDocumentCatalogAdditionalActions catalogActions = catalog.getActions();
        if (catalogActions != null) {
            if (catalogActions.getWC() instanceof PDActionJavaScript) {
                catalogActions.setWC(null);
            }
            if (catalogActions.getWS() instanceof PDActionJavaScript) {
                catalogActions.setWS(null);
            }
            if (catalogActions.getDS() instanceof PDActionJavaScript) {
                catalogActions.setDS(null);
            }
            if (catalogActions.getWP() instanceof PDActionJavaScript) {
                catalogActions.setWP(null);
            }
            if (catalogActions.getDP() instanceof PDActionJavaScript) {
                catalogActions.setDP(null);
            }
        }

        PDAcroForm acroForm = catalog.getAcroForm();
        if (acroForm != null) {
            for (PDField field : acroForm.getFields()) {
                PDFormFieldAdditionalActions actions = field.getActions();
                if (actions != null) {
                    if (actions.getC() instanceof PDActionJavaScript) {
                        actions.setC(null);
                    }
                    if (actions.getF() instanceof PDActionJavaScript) {
                        actions.setF(null);
                    }
                    if (actions.getK() instanceof PDActionJavaScript) {
                        actions.setK(null);
                    }
                    if (actions.getV() instanceof PDActionJavaScript) {
                        actions.setV(null);
                    }
                }
            }
        }

        for (PDPage page : document.getPages()) {
            PDPageAdditionalActions pageActions = page.getActions();
            if (pageActions != null) {
                if (pageActions.getO() instanceof PDActionJavaScript) {
                    pageActions.setO(null);
                }
                if (pageActions.getC() instanceof PDActionJavaScript) {
                    pageActions.setC(null);
                }
            }

            for (PDAnnotation annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationWidget widget) {
                    PDAction action = widget.getAction();
                    if (action instanceof PDActionJavaScript) {
                        widget.setAction(null);
                    }
                }
            }
        }
    }

    public static void sanitizeEmbeddedFiles(PDDocument document) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDDocumentNameDictionary names = catalog.getNames();
        if (names != null) {
            names.setEmbeddedFiles(null);
        }

        for (PDPage page : document.getPages()) {
            List<PDAnnotation> annotations = page.getAnnotations();
            if (annotations != null && !annotations.isEmpty()) {
                annotations.removeIf(
                        annotation -> annotation instanceof PDAnnotationFileAttachment);
            }
        }
    }

    public static void sanitizeXMPMetadata(PDDocument document) {
        if (document.getDocumentCatalog() != null) {
            PDMetadata metadata = document.getDocumentCatalog().getMetadata();
            if (metadata != null) {
                document.getDocumentCatalog().setMetadata(null);
            }
        }
    }

    public static void sanitizeDocumentInfoMetadata(PDDocument document) {
        PDDocumentInformation docInfo = document.getDocumentInformation();
        if (docInfo != null) {
            PDDocumentInformation newInfo = new PDDocumentInformation();
            document.setDocumentInformation(newInfo);
        }
    }

    public static void sanitizeLinks(PDDocument document) throws IOException {
        for (PDPage page : document.getPages()) {
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationLink linkAnnotation) {
                    PDAction action = linkAnnotation.getAction();
                    if ((action instanceof PDActionLaunch || action instanceof PDActionURI)) {
                        linkAnnotation.setAction(null);
                    }
                }
            }
        }
    }

    public static void sanitizeFonts(PDDocument document) {
        for (PDPage page : document.getPages()) {
            if (page != null
                    && page.getResources() != null
                    && page.getResources().getCOSObject() != null) {
                page.getResources().getCOSObject().removeItem(COSName.getPDFName("Font"));
            }
        }
    }
}

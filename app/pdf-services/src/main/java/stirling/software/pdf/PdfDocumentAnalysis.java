package stirling.software.pdf;

import java.io.IOException;
import java.util.*;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.encryption.PDEncryption;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;

/** Exact retained lightweight analysis report shapes over caller-owned documents. */
public final class PdfDocumentAnalysis {
    private PdfDocumentAnalysis() {}

    public static Object getPageCount(PDDocument document, long fileSize) throws IOException {

        return Map.of("pageCount", document.getNumberOfPages());
    }

    public static Object getBasicInfo(PDDocument document, long fileSize) throws IOException {

        Map<String, Object> info = new HashMap<>();
        info.put("pageCount", document.getNumberOfPages());
        info.put("pdfVersion", document.getVersion());
        info.put("fileSize", fileSize);
        return info;
    }

    public static Object getDocumentProperties(PDDocument document, long fileSize)
            throws IOException {

        PDDocumentInformation info = document.getDocumentInformation();
        Map<String, String> properties = new HashMap<>();
        properties.put("title", info.getTitle());
        properties.put("author", info.getAuthor());
        properties.put("subject", info.getSubject());
        properties.put("keywords", info.getKeywords());
        properties.put("creator", info.getCreator());
        properties.put("producer", info.getProducer());
        properties.put(
                "creationDate",
                info.getCreationDate() != null ? info.getCreationDate().toString() : null);
        properties.put(
                "modificationDate",
                info.getModificationDate() != null ? info.getModificationDate().toString() : null);
        return properties;
    }

    public static Object getPageDimensions(PDDocument document, long fileSize) throws IOException {

        List<Map<String, Float>> dimensions = new ArrayList<>();
        PDPageTree pages = document.getPages();

        for (PDPage page : pages) {
            Map<String, Float> pageDim = new HashMap<>();
            pageDim.put("width", page.getBBox().getWidth());
            pageDim.put("height", page.getBBox().getHeight());
            dimensions.add(pageDim);
        }
        return dimensions;
    }

    public static Object getFormFields(PDDocument document, long fileSize) throws IOException {

        Map<String, Object> formInfo = new HashMap<>();
        PDAcroForm form = document.getDocumentCatalog().getAcroForm();

        if (form != null) {
            formInfo.put("fieldCount", form.getFields().size());
            formInfo.put("hasXFA", form.hasXFA());
            formInfo.put("isSignaturesExist", form.isSignaturesExist());
        } else {
            formInfo.put("fieldCount", 0);
            formInfo.put("hasXFA", false);
            formInfo.put("isSignaturesExist", false);
        }
        return formInfo;
    }

    public static Object getAnnotationInfo(PDDocument document, long fileSize) throws IOException {

        Map<String, Object> annotInfo = new HashMap<>();
        int totalAnnotations = 0;
        Map<String, Integer> annotationTypes = new HashMap<>();

        for (PDPage page : document.getPages()) {
            for (PDAnnotation annot : page.getAnnotations()) {
                totalAnnotations++;
                String subType = annot.getSubtype();
                annotationTypes.merge(subType, 1, Integer::sum);
            }
        }

        annotInfo.put("totalCount", totalAnnotations);
        annotInfo.put("typeBreakdown", annotationTypes);
        return annotInfo;
    }

    public static Object getFontInfo(PDDocument document, long fileSize) throws IOException {

        Map<String, Object> fontInfo = new HashMap<>();
        Set<String> fontNames = new HashSet<>();

        for (PDPage page : document.getPages()) {
            PDResources resources = page.getResources();
            if (resources != null) {
                for (COSName font : resources.getFontNames()) {
                    fontNames.add(font.getName());
                }
            }
        }

        fontInfo.put("fontCount", fontNames.size());
        fontInfo.put("fonts", fontNames);
        return fontInfo;
    }

    public static Object getSecurityInfo(PDDocument document, long fileSize) throws IOException {

        Map<String, Object> securityInfo = new HashMap<>();
        PDEncryption encryption = document.getEncryption();

        if (encryption != null) {
            securityInfo.put("isEncrypted", true);
            securityInfo.put("keyLength", encryption.getLength());

            Map<String, Boolean> permissions = new HashMap<>();
            permissions.put("preventPrinting", !document.getCurrentAccessPermission().canPrint());
            permissions.put("preventModify", !document.getCurrentAccessPermission().canModify());
            permissions.put(
                    "preventExtractContent",
                    !document.getCurrentAccessPermission().canExtractContent());
            permissions.put(
                    "preventModifyAnnotations",
                    !document.getCurrentAccessPermission().canModifyAnnotations());

            securityInfo.put("permissions", permissions);
        } else {
            securityInfo.put("isEncrypted", false);
        }

        return securityInfo;
    }

    /** Exports the retained named-script text format; it never evaluates JavaScript. */
    public static String javascript(PDDocument document, String filename) throws IOException {
        StringBuilder script = new StringBuilder();
        boolean foundScript = false;

        if (document.getDocumentCatalog() != null
                && document.getDocumentCatalog().getNames() != null) {
            PDNameTreeNode<PDActionJavaScript> jsTree =
                    document.getDocumentCatalog().getNames().getJavaScript();

            if (jsTree != null) {
                Map<String, PDActionJavaScript> jsEntries = jsTree.getNames();

                for (Map.Entry<String, PDActionJavaScript> entry : jsEntries.entrySet()) {
                    String name = entry.getKey();
                    PDActionJavaScript jsAction = entry.getValue();
                    String jsCodeStr = jsAction.getAction();

                    if (jsCodeStr != null && !jsCodeStr.trim().isEmpty()) {
                        script.append("// File: ")
                                .append(filename)
                                .append(", Script: ")
                                .append(name)
                                .append("\n")
                                .append(jsCodeStr)
                                .append("\n");
                        foundScript = true;
                    }
                }
            }
        }

        if (!foundScript) {
            script =
                    new StringBuilder("PDF '")
                            .append(filename)
                            .append("' does not contain Javascript");
        }

        return script.toString();
    }
}

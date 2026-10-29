package stirling.software.pdf;

import java.awt.Color;
import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType1CFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationHighlight;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.AdobePDFSchema;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.xmpbox.xml.XmpSerializer;

import lombok.extern.slf4j.Slf4j;

/** Existing PDFBox PDF/A conversion algorithms shared by local and legacy adapters. */
@Slf4j
public final class PdfArchive {
    public static final class NonCompliant extends IOException {
        public NonCompliant(String message) {
            super(message);
        }
    }

    public static final class HelperRequired extends IOException {
        public HelperRequired(String message) {
            super(message);
        }
    }

    /**
     * Returns only bytes validated against the requested B-level profile; helper-dependent inputs
     * fail.
     */
    public static byte[] convertLocal(PDDocument document, String requested) throws Exception {
        int part =
                switch (requested.toLowerCase(Locale.ROOT)) {
                    case "pdfa-1" -> 1;
                    case "pdfa", "pdfa-2", "pdfa-2b" -> 2;
                    case "pdfa-3", "pdfa-3b" -> 3;
                    default ->
                            throw new IllegalArgumentException(
                                    "Only local PDF/A-1b, 2b and 3b conversion is connected.");
                };
        PdfProtection.requireOwner(document);
        if (document.getNumberOfPages() > 100)
            throw new IllegalArgumentException(
                    "PDF/A conversion supports at most 100 pages per job.");
        if (!findUnembeddedFontNames(document).isEmpty())
            throw new HelperRequired(
                    "This PDF needs font embedding with an unavailable conversion helper.");
        if (part == 1 && hasTransparentImages(document))
            throw new HelperRequired(
                    "PDF/A-1 transparency conversion requires an unavailable helper. Select PDF/A-2b or 3b.");
        document.setAllSecurityToBeRemoved(true);
        if (part == 1) flattenHighlights(document);
        byte[] result = processWithPDFBox(document, part);
        if (result.length > 128L * 1024 * 1024)
            throw new IllegalArgumentException("Converted PDF exceeds the job output limit.");
        var validation =
                new PdfStandards().validateRequested(new ByteArrayInputStream(result), part + "b");
        if (!validation.isCompliant())
            throw new NonCompliant(
                    "The converted document does not meet the requested PDF/A-"
                            + part
                            + "b standard; no output was returned.");
        return result;
    }

    private static final String ICC_RESOURCE_PATH = "/icc/sRGB2014.icc";
    private static final int PDFA_COMPATIBILITY_POLICY = 1;

    private static final String ANNOTATION_HIGHLIGHT = "Highlight";
    private static final String ANNOTATION_POPUP = "Popup";
    private static final String ANNOTATION_LINK = "Link";

    private static final COSName COS_AF_RELATIONSHIP = COSName.getPDFName("AFRelationship");
    private static final COSName COS_AF = COSName.getPDFName("AF"); // The Associated Files Array
    private static final COSName COS_UF = COSName.getPDFName("UF");
    private static final String AF_RELATIONSHIP_UNSPECIFIED = "Unspecified";

    private static final Map<String, String> MIME_TYPE_MAP =
            Map.ofEntries(
                    Map.entry(".xml", "application/xml"),
                    Map.entry(".json", "application/json"),
                    Map.entry(".txt", "text/plain"),
                    Map.entry(".csv", "text/csv"),
                    Map.entry(".pdf", "application/pdf"),
                    Map.entry(".png", "image/png"),
                    Map.entry(".jpg", "image/jpeg"),
                    Map.entry(".jpeg", "image/jpeg"),
                    Map.entry(".gif", "image/gif"),
                    Map.entry(".html", "text/html"),
                    Map.entry(".htm", "text/html"),
                    Map.entry(".zip", "application/zip"),
                    Map.entry(".doc", "application/msword"),
                    Map.entry(
                            ".docx",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    Map.entry(".xls", "application/vnd.ms-excel"),
                    Map.entry(
                            ".xlsx",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    Map.entry(".ppt", "application/vnd.ms-powerpoint"),
                    Map.entry(
                            ".pptx",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
                    Map.entry(".svg", "image/svg+xml"),
                    Map.entry(".webp", "image/webp"),
                    Map.entry(".mp3", "audio/mpeg"),
                    Map.entry(".mp4", "video/mp4"),
                    Map.entry(".wav", "audio/wav"),
                    Map.entry(".avi", "video/x-msvideo"),
                    Map.entry(".tar", "application/x-tar"),
                    Map.entry(".gz", "application/gzip"),
                    Map.entry(".rar", "application/vnd.rar"),
                    Map.entry(".7z", "application/x-7z-compressed"));

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    public static void fixCidSetIssues(PDDocument document) {
        for (PDPage page : document.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) continue;

            for (COSName fontName : resources.getFontNames()) {
                try {
                    PDFont font = resources.getFont(fontName);
                    if (font == null) continue;

                    PDFontDescriptor descriptor = font.getFontDescriptor();
                    if (descriptor == null) continue;

                    COSDictionary fontDict = descriptor.getCOSObject();

                    // Remove invalid or incomplete CIDSet entries for PDF/A-1 compliance
                    // PDF/A-1 requires CIDSet to be present and complete for subsetted CIDFonts
                    // For PDF/A-2+, CIDSet is optional but must be complete if present
                    COSBase cidSet = fontDict.getDictionaryObject(COSName.getPDFName("CIDSet"));
                    if (cidSet != null) {
                        // If CIDSet exists but may be invalid, remove it to avoid validation errors
                        // This is safer than trying to fix incomplete CIDSet streams
                        fontDict.removeItem(COSName.getPDFName("CIDSet"));
                        log.debug(
                                "Removed potentially invalid CIDSet from font {}", font.getName());
                    }
                } catch (Exception e) {
                    log.debug("Error processing CIDSet for font: {}", e.getMessage());
                }
            }
        }
    }

    public static Set<String> findUnembeddedFontNames(PDDocument doc) throws IOException {
        Set<String> missing = new HashSet<>(16);
        for (PDPage page : doc.getPages()) {
            PDResources res = page.getResources();
            for (COSName name : res.getFontNames()) {
                PDFont font = res.getFont(name);
                if (font != null && !font.isEmbedded()) {
                    missing.add(font.getName());
                }
            }
        }
        return missing;
    }

    public static void fixType1FontCharSet(PDDocument document) throws IOException {
        for (PDPage page : document.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) continue;

            for (COSName fontName : resources.getFontNames()) {
                try {
                    PDFont font = resources.getFont(fontName);
                    if (font == null) continue;

                    String fontNameStr = font.getName();
                    if (fontNameStr == null) continue;

                    PDFontDescriptor descriptor = font.getFontDescriptor();
                    if (descriptor == null) continue;

                    // Check if this is a Type1 font
                    boolean isType1 =
                            isType1Font(font)
                                    || descriptor.getFontFile() != null
                                    || (descriptor.getFontFile2() == null
                                            && descriptor.getFontFile3() == null);

                    if (isType1) {
                        COSDictionary descDict = descriptor.getCOSObject();
                        String existingCharSet = descDict.getString(COSName.CHAR_SET);

                        // Check if font is embedded and if CharSet might be invalid
                        boolean fontEmbedded = font.isEmbedded();
                        boolean hasFontFile =
                                descriptor.getFontFile() != null
                                        || descriptor.getFontFile2() != null
                                        || descriptor.getFontFile3() != null;

                        // For PDF/A compliance: if CharSet exists but font is subsetted or
                        // we can't verify it matches the font file, remove it to avoid validation
                        // errors
                        if (existingCharSet != null && !existingCharSet.trim().isEmpty()) {
                            // If the font appears to be subsetted (indicated by subset prefix in
                            // name)
                            // or if we can't verify the CharSet is correct, remove it
                            if (fontNameStr.contains("+") || fontNameStr.contains("Subset")) {
                                descDict.removeItem(COSName.CHAR_SET);
                                log.debug(
                                        "Removed potentially invalid CharSet from subsetted Type1 font: {}",
                                        fontNameStr);
                            } else if (!hasFontFile && fontEmbedded) {
                                // Font is embedded but we can't verify CharSet, remove it
                                descDict.removeItem(COSName.CHAR_SET);
                                log.debug(
                                        "Removed unverifiable CharSet from embedded Type1 font: {}",
                                        fontNameStr);
                            }
                        } else if (existingCharSet == null || existingCharSet.trim().isEmpty()) {
                            // Only add CharSet if font is not subsetted and we can verify it
                            if (!fontNameStr.contains("+")
                                    && !fontNameStr.contains("Subset")
                                    && hasFontFile) {
                                String glyphSet = buildStandardType1GlyphSet();
                                if (!glyphSet.isEmpty()) {
                                    descDict.setString(COSName.CHAR_SET, glyphSet);
                                    log.debug(
                                            "Added missing CharSet for Type1 font {} with {} glyphs",
                                            fontNameStr,
                                            countGlyphs(glyphSet));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn(
                            "Error processing font descriptor for page resource: {}",
                            e.getMessage());
                }
            }
        }
    }

    public static int countGlyphs(String charSet) {
        if (charSet == null || charSet.isEmpty()) return 0;
        // CharSet format: /glyph1/glyph2/glyph3...
        return (int) charSet.chars().filter(c -> c == '/').count();
    }

    public static void sanitizePdfA(COSBase base, int pdfaPart) {
        sanitizePdfA(base, pdfaPart, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static void sanitizePdfA(COSBase base, int pdfaPart, Set<COSBase> visited, int depth) {
        if (base == null || !visited.add(base)) return;
        if (depth > 128 || visited.size() > 100000)
            throw new IllegalArgumentException("PDF object graph exceeds conversion limits.");
        if (base instanceof COSDictionary dict) {
            if (pdfaPart == 3) {
                COSName type = dict.getCOSName(COSName.TYPE);
                if (COSName.FILESPEC.equals(type) || dict.containsKey(COSName.EF)) {
                    return; // Don't sanitize embedded file structures
                }
            }

            if (pdfaPart == 1) {
                COSBase group = dict.getDictionaryObject(COSName.GROUP);
                if (group instanceof COSDictionary gDict
                        && COSName.TRANSPARENCY.equals(gDict.getCOSName(COSName.S))) {
                    dict.removeItem(COSName.GROUP);
                }

                dict.removeItem(COSName.SMASK);
                dict.removeItem(COSName.CA);
                dict.removeItem(COSName.getPDFName("ca"));
            }

            if (dict.containsKey(COSName.INTERPOLATE)
                    && dict.getBoolean(COSName.INTERPOLATE, true)) {
                dict.setBoolean(COSName.INTERPOLATE, false);
            }

            dict.removeItem(COSName.JAVA_SCRIPT);
            dict.removeItem(COSName.getPDFName("JS"));
            dict.removeItem(COSName.getPDFName("RichMedia"));
            dict.removeItem(COSName.getPDFName("Movie"));
            dict.removeItem(COSName.getPDFName("Sound"));
            dict.removeItem(COSName.getPDFName("Launch"));

            if (pdfaPart != 3) {
                dict.removeItem(COSName.URI);
            }
            dict.removeItem(COSName.getPDFName("GoToR"));

            if (pdfaPart != 3) {
                dict.removeItem(COSName.EMBEDDED_FILES);
                dict.removeItem(COSName.FILESPEC);
            }

            for (Map.Entry<COSName, COSBase> entry : dict.entrySet()) {
                if (pdfaPart == 3) {
                    COSName key = entry.getKey();
                    if (COSName.EF.equals(key)
                            || COSName.EMBEDDED_FILES.equals(key)
                            || COSName.FILESPEC.equals(key)
                            || COSName.F.equals(key)
                            || COSName.UF.equals(key)) {
                        continue; // Don't recurse into embedded file content
                    }
                }
                sanitizePdfA(entry.getValue(), pdfaPart, visited, depth + 1);
            }

        } else if (base instanceof COSArray arr) {
            for (COSBase item : arr) {
                sanitizePdfA(item, pdfaPart, visited, depth + 1);
            }
        }
    }

    public static void removeElementsForPdfA(PDDocument doc, int pdfaPart) {

        if (pdfaPart == 1) {
            doc.getDocumentCatalog().getCOSObject().removeItem(COSName.getPDFName("OCProperties"));
        }

        if (pdfaPart == 3) {
            ensureEmbeddedFilesAFRelationship(doc);
        }

        for (PDPage page : doc.getPages()) {
            if (pdfaPart == 1) {
                page.setAnnotations(Collections.emptyList());
            }
            PDResources res = page.getResources();
            sanitizePdfA(page.getCOSObject(), pdfaPart);

            if (res != null) {
                for (COSName name : res.getXObjectNames()) {
                    try {
                        PDXObject xo = res.getXObject(name);
                        if (xo instanceof PDFormXObject form) {
                            sanitizePdfA(form.getCOSObject(), pdfaPart);
                        } else if (xo instanceof PDImageXObject img) {
                            sanitizePdfA(img.getCOSObject(), pdfaPart);
                        }
                    } catch (IOException ioe) {
                        log.error("Cannot load XObject {}: {}", name.getName(), ioe.getMessage());
                    }
                }
            }
        }
    }

    public static String buildStandardType1GlyphSet() {
        Set<String> glyphNames = new LinkedHashSet<>();

        String[] standardGlyphs = {
            ".notdef",
            ".null",
            "nonmarkingreturn",
            "space",
            "exclam",
            "quotedbl",
            "numbersign",
            "dollar",
            "percent",
            "ampersand",
            "quoteright",
            "parenleft",
            "parenright",
            "asterisk",
            "plus",
            "comma",
            "hyphen",
            "period",
            "slash",
            "zero",
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "colon",
            "semicolon",
            "less",
            "equal",
            "greater",
            "question",
            "at",
            "A",
            "B",
            "C",
            "D",
            "E",
            "F",
            "G",
            "H",
            "I",
            "J",
            "K",
            "L",
            "M",
            "N",
            "O",
            "P",
            "Q",
            "R",
            "S",
            "T",
            "U",
            "V",
            "W",
            "X",
            "Y",
            "Z",
            "bracketleft",
            "backslash",
            "bracketright",
            "asciicircum",
            "underscore",
            "quoteleft",
            "a",
            "b",
            "c",
            "d",
            "e",
            "f",
            "g",
            "h",
            "i",
            "j",
            "k",
            "l",
            "m",
            "n",
            "o",
            "p",
            "q",
            "r",
            "s",
            "t",
            "u",
            "v",
            "w",
            "x",
            "y",
            "z",
            "braceleft",
            "bar",
            "braceright",
            "asciitilde",
            "exclamdown",
            "cent",
            "sterling",
            "currency",
            "yen",
            "brokenbar",
            "section",
            "dieresis",
            "copyright",
            "ordfeminine",
            "guillemotleft",
            "logicalnot",
            "uni00AD",
            "registered",
            "macron",
            "degree",
            "plusminus",
            "twosuperior",
            "threesuperior",
            "acute",
            "mu",
            "paragraph",
            "periodcentered",
            "cedilla",
            "onesuperior",
            "ordmasculine",
            "guillemotright",
            "onequarter",
            "onehalf",
            "threequarters",
            "questiondown",
            "Agrave",
            "Aacute",
            "Acircumflex",
            "Atilde",
            "Adieresis",
            "Aring",
            "AE",
            "Ccedilla",
            "Egrave",
            "Eacute",
            "Ecircumflex",
            "Edieresis",
            "Igrave",
            "Iacute",
            "Icircumflex",
            "Idieresis",
            "Eth",
            "Ntilde",
            "Ograve",
            "Oacute",
            "Ocircumflex",
            "Otilde",
            "Odieresis",
            "multiply",
            "Oslash",
            "Ugrave",
            "Uacute",
            "Ucircumflex",
            "Udieresis",
            "Yacute",
            "Thorn",
            "germandbls",
            "agrave",
            "aacute",
            "acircumflex",
            "atilde",
            "adieresis",
            "aring",
            "ae",
            "ccedilla",
            "egrave",
            "eacute",
            "ecircumflex",
            "edieresis",
            "igrave",
            "iacute",
            "icircumflex",
            "idieresis",
            "eth",
            "ntilde",
            "ograve",
            "oacute",
            "ocircumflex",
            "otilde",
            "odieresis",
            "divide",
            "oslash",
            "ugrave",
            "uacute",
            "ucircumflex",
            "udieresis",
            "yacute",
            "thorn",
            "ydieresis"
        };

        Collections.addAll(glyphNames, standardGlyphs);

        return String.join(" ", glyphNames);
    }

    public static byte[] processWithPDFBox(PDDocument document, int pdfaPart) throws Exception {
        removeElementsForPdfA(document, pdfaPart);

        document.getDocument().setVersion(pdfaPart == 1 ? 1.4f : 1.7f);

        mergeAndAddXmpMetadata(document, pdfaPart);

        addICCProfileIfNotPresent(document);

        // Fix CIDSet issues for PDF/A compliance
        if (pdfaPart == 1) {
            fixCidSetIssues(document);
        }

        fixType1FontCharSet(document);

        PDDocumentCatalog catalog = document.getDocumentCatalog();
        catalog.setMetadata(document.getDocumentCatalog().getMetadata());

        PDViewerPreferences viewerPrefs = new PDViewerPreferences(catalog.getCOSObject());
        viewerPrefs.setDisplayDocTitle(true);
        catalog.setViewerPreferences(viewerPrefs);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CompressParameters compressParams =
                pdfaPart == 1 ? CompressParameters.NO_COMPRESSION : new CompressParameters();

        document.save(baos, compressParams);
        log.debug("PDF/A-{} document processed with PDFBox", pdfaPart);

        return baos.toByteArray();
    }

    public static void ensureEmbeddedFilesAFRelationship(PDDocument doc) {
        PDDocumentCatalog catalog = doc.getDocumentCatalog();
        PDDocumentNameDictionary names = catalog.getNames();
        if (names == null) return;

        PDEmbeddedFilesNameTreeNode embeddedFiles = names.getEmbeddedFiles();
        if (embeddedFiles == null) return;

        try {
            processEmbeddedFilesForAFRelationship(embeddedFiles);
        } catch (IOException e) {
            log.warn("Could not process embedded files AFRelationship: {}", e.getMessage());
        }
    }

    public static void processEmbeddedFilesForAFRelationship(
            PDEmbeddedFilesNameTreeNode embeddedFiles) throws IOException {
        Map<String, PDComplexFileSpecification> fileSpecs = embeddedFiles.getNames();
        if (fileSpecs == null) return;

        for (PDComplexFileSpecification fileSpec : fileSpecs.values()) {
            COSDictionary fileSpecDict = fileSpec.getCOSObject();
            if (!fileSpecDict.containsKey(COS_AF_RELATIONSHIP)) {
                fileSpecDict.setName(COS_AF_RELATIONSHIP, AF_RELATIONSHIP_UNSPECIFIED);
            }
        }
    }

    public static boolean hasTransparentImages(PDDocument doc) {
        for (PDPage page : doc.getPages()) {
            PDResources res = page.getResources();
            if (res == null) continue;
            for (COSName name : res.getXObjectNames()) {
                try {
                    PDXObject xo = res.getXObject(name);
                    if (xo instanceof PDImageXObject img) {
                        COSDictionary dict = img.getCOSObject();
                        if (dict.containsKey(COSName.SMASK)) return true;
                        COSBase g = dict.getDictionaryObject(COSName.GROUP);
                        if (g instanceof COSDictionary gd
                                && COSName.TRANSPARENCY.equals(gd.getCOSName(COSName.S))) {
                            return true;
                        }
                        if (dict.getBoolean(COSName.INTERPOLATE, false)) return true;
                    }
                } catch (IOException ioe) {
                    log.error("Error processing XObject {}: {}", name.getName(), ioe.getMessage());
                }
            }
        }
        return false;
    }

    private static void flattenHighlights(PDDocument document) throws IOException {
        for (PDPage page : document.getPages()) {
            List<PDAnnotation> annotations = page.getAnnotations();
            for (PDAnnotation annot : annotations) {
                if (ANNOTATION_HIGHLIGHT.equals(annot.getSubtype())
                        && annot instanceof PDAnnotationHighlight highlight) {
                    float[] colorComponents =
                            highlight.getColor() != null
                                    ? highlight.getColor().getComponents()
                                    : new float[] {1f, 1f, 0f};
                    Color highlightColor =
                            new Color(colorComponents[0], colorComponents[1], colorComponents[2]);

                    float[] quadPoints = highlight.getQuadPoints();
                    if (quadPoints != null) {
                        try (PDPageContentStream cs =
                                new PDPageContentStream(
                                        document,
                                        page,
                                        PDPageContentStream.AppendMode.PREPEND,
                                        true,
                                        true)) {

                            cs.setStrokingColor(highlightColor);
                            cs.setLineWidth(0.05f);
                            float spacing = 2f;
                            for (int i = 0; i < quadPoints.length; i += 8) {
                                float minX =
                                        Math.min(
                                                Math.min(quadPoints[i], quadPoints[i + 2]),
                                                Math.min(quadPoints[i + 4], quadPoints[i + 6]));
                                float maxX =
                                        Math.max(
                                                Math.max(quadPoints[i], quadPoints[i + 2]),
                                                Math.max(quadPoints[i + 4], quadPoints[i + 6]));
                                float minY =
                                        Math.min(
                                                Math.min(quadPoints[i + 1], quadPoints[i + 3]),
                                                Math.min(quadPoints[i + 5], quadPoints[i + 7]));
                                float maxY =
                                        Math.max(
                                                Math.max(quadPoints[i + 1], quadPoints[i + 3]),
                                                Math.max(quadPoints[i + 5], quadPoints[i + 7]));

                                float width = maxX - minX;
                                float height = maxY - minY;

                                for (float y = minY; y <= maxY; y += spacing) {
                                    float len = Math.min(width, maxY - y);
                                    cs.moveTo(minX, y);
                                    cs.lineTo(minX + len, y + len);
                                }
                                for (float x = minX + spacing; x <= maxX; x += spacing) {
                                    float len = Math.min(maxX - x, height);
                                    cs.moveTo(x, minY);
                                    cs.lineTo(x + len, minY + len);
                                }
                            }

                            cs.stroke();
                        }
                    }

                    page.getAnnotations().remove(highlight);
                    COSDictionary pageDict = page.getCOSObject();

                    if (pageDict.containsKey(COSName.GROUP)) {
                        COSDictionary groupDict =
                                (COSDictionary) pageDict.getDictionaryObject(COSName.GROUP);

                        if (groupDict != null
                                && COSName.TRANSPARENCY
                                        .getName()
                                        .equalsIgnoreCase(groupDict.getNameAsString(COSName.S))) {
                            pageDict.removeItem(COSName.GROUP);
                        }
                    }
                }
            }
        }
        // Save the modified document to a temporary file.
    }

    public static File preProcessHighlights(File inputPdf) throws Exception {

        try (PDDocument document = Loader.loadPDF(inputPdf)) {

            flattenHighlights(document);

            File preProcessedFile = Files.createTempFile("preprocessed_", ".pdf").toFile();
            document.save(preProcessedFile);
            return preProcessedFile;
        }
    }

    public static boolean isType1Font(PDFont font) {
        return font instanceof PDType1Font || font instanceof PDType1CFont;
    }

    public static void mergeAndAddXmpMetadata(PDDocument document, int pdfaPart) throws Exception {
        PDMetadata existingMetadata = document.getDocumentCatalog().getMetadata();
        XMPMetadata xmp;

        if (existingMetadata != null) {
            try (InputStream xmpStream = existingMetadata.createInputStream()) {
                byte[] xmpBytes = xmpStream.readNBytes(2 * 1024 * 1024 + 1);
                if (xmpBytes.length > 2 * 1024 * 1024)
                    throw new IOException("XMP metadata exceeds conversion limits.");
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature(
                        "http://xml.org/sax/features/external-parameter-entities", false);
                factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmpBytes));
                DomXmpParser parser = new DomXmpParser();
                parser.setStrictParsing(false);
                xmp = parser.parse(new ByteArrayInputStream(xmpBytes));
            } catch (Exception e) {
                xmp = XMPMetadata.createXMPMetadata();
            }
        } else {
            xmp = XMPMetadata.createXMPMetadata();
        }

        PDDocumentInformation docInfo = document.getDocumentInformation();
        if (docInfo == null) {
            docInfo = new PDDocumentInformation();
        }

        String originalCreator = Optional.ofNullable(docInfo.getCreator()).orElse("Unknown");
        String originalProducer = Optional.ofNullable(docInfo.getProducer()).orElse("Unknown");

        DublinCoreSchema dcSchema = xmp.getDublinCoreSchema();
        if (dcSchema != null) {
            List<String> existingCreators = dcSchema.getCreators();
            if (existingCreators != null) {
                for (String creator : new ArrayList<>(existingCreators)) {
                    dcSchema.removeCreator(creator);
                }
            }
        } else {
            dcSchema = xmp.createAndAddDublinCoreSchema();
        }
        dcSchema.addCreator(originalCreator);

        PDFAIdentificationSchema pdfaSchema =
                (PDFAIdentificationSchema) xmp.getSchema(PDFAIdentificationSchema.class);
        if (pdfaSchema == null) {
            pdfaSchema = xmp.createAndAddPDFAIdentificationSchema();
        }
        pdfaSchema.setPart(pdfaPart);
        pdfaSchema.setConformance("B");

        XMPBasicSchema xmpBasicSchema = xmp.getXMPBasicSchema();
        if (xmpBasicSchema == null) {
            xmpBasicSchema = xmp.createAndAddXMPBasicSchema();
        }

        AdobePDFSchema adobePdfSchema = xmp.getAdobePDFSchema();
        if (adobePdfSchema == null) {
            adobePdfSchema = xmp.createAndAddAdobePDFSchema();
        }

        docInfo.setCreator(originalCreator);
        xmpBasicSchema.setCreatorTool(originalCreator);

        docInfo.setProducer(originalProducer);
        adobePdfSchema.setProducer(originalProducer);

        String originalAuthor = docInfo.getAuthor();
        if (originalAuthor != null && !originalAuthor.isBlank()) {
            docInfo.setAuthor(null);
            if (!originalCreator.equals(originalAuthor)) {
                dcSchema.addCreator(originalAuthor);
            }
        }

        String title = docInfo.getTitle();
        if (title != null && !title.isBlank()) {
            dcSchema.setTitle(title);
        }
        String subject = docInfo.getSubject();
        if (subject != null && !subject.isBlank()) {
            dcSchema.addSubject(subject);
        }
        String keywords = docInfo.getKeywords();
        if (keywords != null && !keywords.isBlank()) {
            adobePdfSchema.setKeywords(keywords);
        }

        Instant nowInstant = Instant.now();
        ZonedDateTime nowZdt = ZonedDateTime.ofInstant(nowInstant, ZoneId.of("UTC"));

        Instant creationInstant;
        Calendar originalCreationDate = docInfo.getCreationDate();
        if (originalCreationDate != null) {
            creationInstant = originalCreationDate.toInstant();
        } else {
            creationInstant = nowInstant;
        }
        ZonedDateTime creationZdt = ZonedDateTime.ofInstant(creationInstant, ZoneId.of("UTC"));

        GregorianCalendar creationCal = GregorianCalendar.from(creationZdt);
        GregorianCalendar modificationCal = GregorianCalendar.from(nowZdt);

        docInfo.setCreationDate(creationCal);
        xmpBasicSchema.setCreateDate(creationCal);

        docInfo.setModificationDate(modificationCal);
        xmpBasicSchema.setModifyDate(modificationCal);
        xmpBasicSchema.setMetadataDate(modificationCal);

        ByteArrayOutputStream xmpOut = new ByteArrayOutputStream();
        new XmpSerializer().serialize(xmp, xmpOut, true);

        PDMetadata newMetadata = new PDMetadata(document);
        newMetadata.importXMPMetadata(xmpOut.toByteArray());
        document.getDocumentCatalog().setMetadata(newMetadata);
    }

    public static void addICCProfileIfNotPresent(PDDocument document) {
        if (document.getDocumentCatalog().getOutputIntents().isEmpty()) {
            try (InputStream colorProfile =
                    PdfArchive.class.getResourceAsStream(ICC_RESOURCE_PATH)) {
                if (colorProfile == null) {
                    throw new IllegalArgumentException("Bundled ICC profile is unavailable.");
                }
                PDOutputIntent outputIntent = new PDOutputIntent(document, colorProfile);
                // PDF/A compliant output intent settings
                outputIntent.setInfo("sRGB IEC61966-2.1");
                outputIntent.setOutputCondition("sRGB IEC61966-2.1");
                outputIntent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
                outputIntent.setRegistryName("http://www.color.org");
                document.getDocumentCatalog().addOutputIntent(outputIntent);
                log.debug("Added ICC color profile for PDF/A compliance");
            } catch (Exception e) {
                log.error("Failed to load ICC profile: {}", e.getMessage());
                throw new RuntimeException("ICC profile loading failed for PDF/A compliance", e);
            }
        }
    }
}

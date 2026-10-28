package stirling.software.worker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import stirling.software.pdf.PdfAttachmentFiles;
import stirling.software.pdf.PdfAttachments;
import stirling.software.pdf.PdfCertificates;
import stirling.software.pdf.PdfDocumentAnalysis;
import stirling.software.pdf.PdfDocumentInfo;
import stirling.software.pdf.PdfForms;
import stirling.software.pdf.PdfImageConversion;
import stirling.software.pdf.PdfImageExtraction;
import stirling.software.pdf.PdfMetadataEditor;
import stirling.software.pdf.PdfPageCrop;
import stirling.software.pdf.PdfPageFlattener;
import stirling.software.pdf.PdfPageNumbers;
import stirling.software.pdf.PdfPageScale;
import stirling.software.pdf.PdfPageSelection;
import stirling.software.pdf.PdfPortfolio;
import stirling.software.pdf.PdfProtection;
import stirling.software.pdf.PdfRasterizer;
import stirling.software.pdf.PdfSanitizer;
import stirling.software.pdf.PdfSelectedImage;
import stirling.software.pdf.PdfStamp;
import stirling.software.pdf.PdfStandards;
import stirling.software.pdf.PdfWatermarks;

/**
 * One private job per process. Stdio carries bounded JSON metadata; files stay inside its granted
 * job directory.
 */
public final class WorkerMain {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Set<String> OPERATIONS =
            Set.of(
                    "pdf-text-export",
                    "pdf-image-export",
                    "image-pdf-create",
                    "image-extract",
                    "attachments",
                    "portfolio-create",
                    "portfolio-flatten",
                    "document-inspect",
                    "stamp",
                    "page-crop",
                    "page-scale",
                    "page-numbers",
                    "watermark",
                    "sanitize",
                    "aes-encrypt",
                    "password-protect",
                    "decrypt",
                    "ocr",
                    "cert-sign-invisible",
                    "cert-sign-pkcs12",
                    "signature-integrity",
                    "signature-remove",
                    "metadata-update",
                    "forms-unlock",
                    "flatten");

    private WorkerMain() {}

    public static void main(String[] args) throws Exception {
        for (String property :
                List.of(
                        "javax.xml.accessExternalDTD",
                        "javax.xml.accessExternalSchema",
                        "javax.xml.accessExternalStylesheet")) System.setProperty(property, "");
        if (args.length == 1 && args[0].equals("--capabilities")) {
            emit(Map.of("protocol", 1, "operations", capabilities()));
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("One job directory is required.");
        var root = Path.of(args[0]).toRealPath();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1 && c != '\n') {
            if (line.length() >= 65536) throw new IOException("Request header too large.");
            line.append((char) c);
        }
        String id = "";
        try {
            var request = JSON.fromJson(line.toString(), JsonObject.class);
            id = request.get("requestId").getAsString();
            if (!id.matches("[a-zA-Z0-9-]{1,80}")) throw new IOException("Invalid request ID.");
            String operation = request.get("operation").getAsString();
            if (!capabilities().contains(operation))
                throw new IOException("Operation unavailable.");
            var input = selected(root, request.get("input").getAsString());
            var options = request.getAsJsonObject("options");
            if (options == null) options = new JsonObject();
            emit(Map.of("requestId", id, "type", "progress", "done", 0, "total", 1));
            var result = process(operation, root, input, options);
            emit(
                    Map.of(
                            "requestId",
                            id,
                            "type",
                            "result",
                            "file",
                            result.getFileName().toString(),
                            "bytes",
                            Files.size(result),
                            "contentType",
                            result.toString().endsWith(".bin")
                                    ? operation.equals("pdf-image-export")
                                            ? "image/"
                                                    + PdfImageConversion.format(
                                                            text(options, "imageFormat", "png"))
                                            : "application/octet-stream"
                                    : result.toString().endsWith(".txt")
                                            ? "text/plain; charset=utf-8"
                                            : result.toString().endsWith(".zip")
                                                    ? "application/zip"
                                                    : result.toString().endsWith(".json")
                                                            ? "application/json"
                                                            : "application/pdf"));
        } catch (Exception failure) {
            System.err.println("Worker failure type: " + failure.getClass().getSimpleName());
            // Requests can contain credentials; neither their text nor exception messages belong in
            // logs.
            emit(
                    Map.of(
                            "requestId",
                            id,
                            "type",
                            "error",
                            "code",
                            failure instanceof UnsupportedStructure
                                    ? "UNSUPPORTED_DOCUMENT_STRUCTURE"
                                    : failure instanceof InspectionDenied
                                            ? "INSPECTION_ACCESS_DENIED"
                                            : "PROCESSING_FAILED",
                            "message",
                            "The selected operation could not complete. Check its options, document permissions and private helper availability."));
            System.exit(1);
        }
    }

    private static List<String> capabilities() {
        return OPERATIONS.stream()
                .filter(operation -> !operation.equals("ocr") || helper() != null)
                .filter(
                        operation ->
                                !(operation.equals("watermark") || operation.equals("stamp"))
                                        || System.getenv("SOOTAN_WORKER_FONTS") != null)
                .sorted()
                .toList();
    }

    private static Path selected(Path root, String name) throws IOException {
        if (!name.matches("input-[a-zA-Z0-9-]+\\.pdf"))
            throw new IOException("Invalid selected file.");
        Path path = root.resolve(name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().getParent().equals(root))
            throw new IOException("Input is outside the job grant.");
        return path;
    }

    private static String color(JsonObject options) throws IOException {
        String color = text(options, "colorType", "color");
        if (color.equals("grayscale")) color = "greyscale";
        if (!Set.of("color", "greyscale", "blackwhite").contains(color))
            throw new IOException("Unknown color type.");
        return color;
    }

    private static String attachmentName(String name) throws IOException {
        if (name.isBlank() || name.length() > 1024 || name.indexOf('\0') >= 0)
            throw new IOException("Invalid attachment name.");
        return name;
    }

    private static List<PdfAttachmentFiles.SelectedFile> attachmentGrants(
            Path root, JsonObject options) throws IOException {
        if (!options.has("selectedFiles")) throw new IOException("No selected files.");
        var result = new ArrayList<PdfAttachmentFiles.SelectedFile>();
        var ids = new java.util.HashSet<Integer>();
        for (var item : options.getAsJsonArray("selectedFiles")) {
            var entry = item.getAsJsonObject();
            int index = entry.get("index").getAsInt();
            if (index < 0 || index >= 100 || !ids.add(index))
                throw new IOException("Invalid attachment grant.");
            Path file = root.resolve("attachment-" + index + ".bin");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !file.toRealPath().getParent().equals(root))
                throw new IOException("Invalid attachment grant.");
            String name = attachmentName(entry.get("name").getAsString());
            String type = entry.get("contentType").getAsString();
            if (type.length() > 255 || !type.matches("[a-zA-Z0-9!#$&^_.+/-]*"))
                throw new IOException("Invalid attachment MIME type.");
            result.add(
                    new PdfAttachmentFiles.SelectedFile(
                            name, type, Files.size(file), () -> Files.newInputStream(file)));
        }
        PdfAttachmentFiles.validate(result);
        return result;
    }

    private static String text(JsonObject options, String key, String fallback) {
        return options.has(key) ? options.get(key).getAsString() : fallback;
    }

    private static boolean flag(JsonObject options, String key) {
        return options.has(key) && options.get(key).getAsBoolean();
    }

    private static Path process(String operation, Path root, Path input, JsonObject options)
            throws Exception {
        if (operation.equals("password-protect")) operation = "aes-encrypt";
        if (operation.equals("cert-sign-pkcs12")) operation = "cert-sign-invisible";
        Set<String> allowed =
                switch (operation) {
                    case "pdf-text-export" ->
                            Set.of("sourcePassword", "outputFormat", "useStirlingOfficeConvert");
                    case "pdf-image-export" ->
                            Set.of(
                                    "sourcePassword",
                                    "imageFormat",
                                    "colorType",
                                    "dpi",
                                    "singleOrMultiple",
                                    "pageNumbers",
                                    "includeAnnotations",
                                    "filename");
                    case "image-pdf-create" ->
                            Set.of("selectedFiles", "fitOption", "autoRotate", "colorType");
                    case "image-extract" -> Set.of("sourcePassword", "format", "filename");
                    case "attachments" ->
                            Set.of(
                                    "sourcePassword",
                                    "action",
                                    "attachmentName",
                                    "newName",
                                    "opsJson",
                                    "convertToPdfA3b",
                                    "selectedFiles");
                    case "portfolio-create" -> Set.of("coverTitle", "selectedFiles");
                    case "portfolio-flatten" -> Set.of("sourcePassword");
                    case "document-inspect" -> Set.of("sourcePassword", "report", "filename");
                    case "stamp" ->
                            Set.of(
                                    "sourcePassword",
                                    "stampType",
                                    "stampText",
                                    "alphabet",
                                    "fontSize",
                                    "rotation",
                                    "opacity",
                                    "position",
                                    "overrideX",
                                    "overrideY",
                                    "customColor",
                                    "customMargin",
                                    "pageNumbers",
                                    "filename");
                    case "page-crop" ->
                            Set.of(
                                    "sourcePassword",
                                    "autoCrop",
                                    "pageNumbers",
                                    "x",
                                    "y",
                                    "width",
                                    "height",
                                    "removeDataOutsideCrop");
                    case "page-scale" ->
                            Set.of("sourcePassword", "pageSize", "orientation", "scaleFactor");
                    case "page-numbers" ->
                            Set.of(
                                    "sourcePassword",
                                    "customMargin",
                                    "position",
                                    "startingNumber",
                                    "pagesToNumber",
                                    "customText",
                                    "zeroPad",
                                    "fontSize",
                                    "fontType",
                                    "fontColor",
                                    "filename",
                                    "pageNumbers");
                    case "watermark" ->
                            Set.of(
                                    "sourcePassword",
                                    "watermarkType",
                                    "watermarkText",
                                    "alphabet",
                                    "fontSize",
                                    "rotation",
                                    "opacity",
                                    "widthSpacer",
                                    "heightSpacer",
                                    "customColor",
                                    "convertPDFToImage");
                    case "sanitize" ->
                            Set.of(
                                    "sourcePassword",
                                    "removeJavaScript",
                                    "removeEmbeddedFiles",
                                    "removeXMPMetadata",
                                    "removeMetadata",
                                    "removeLinks",
                                    "removeFonts");
                    case "aes-encrypt" ->
                            Set.of(
                                    "sourcePassword",
                                    "ownerPassword",
                                    "password",
                                    "keyLength",
                                    "preserveExisting",
                                    "preventAssembly",
                                    "preventExtractContent",
                                    "preventExtractForAccessibility",
                                    "preventFillInForm",
                                    "preventModify",
                                    "preventModifyAnnotations",
                                    "preventPrinting",
                                    "preventPrintingFaithful");
                    case "decrypt" ->
                            Set.of(
                                    "sourcePassword",
                                    "password",
                                    "ownerPassword",
                                    "removeAll",
                                    "retainedOpeningPassword");
                    case "ocr" ->
                            Set.of(
                                    "languages",
                                    "sidecar",
                                    "deskew",
                                    "rotatePages",
                                    "clean",
                                    "cleanFinal",
                                    "removeImagesAfter",
                                    "ocrRenderType",
                                    "ocrType");
                    case "cert-sign-invisible" ->
                            Set.of(
                                    "certType",
                                    "password",
                                    "reason",
                                    "location",
                                    "name",
                                    "showSignature",
                                    "pageNumber",
                                    "showLogo");
                    case "signature-remove" -> Set.of("sourcePassword");
                    case "metadata-update" ->
                            Set.of(
                                    "sourcePassword",
                                    "deleteAll",
                                    "author",
                                    "creationDate",
                                    "creator",
                                    "keywords",
                                    "modificationDate",
                                    "producer",
                                    "subject",
                                    "title",
                                    "trapped",
                                    "allRequestParams");
                    case "forms-unlock" -> Set.of("sourcePassword");
                    case "flatten" -> Set.of("sourcePassword", "flattenOnlyForms", "renderDpi");
                    case "signature-integrity" -> Set.of();
                    default -> Set.of();
                };
        if (!allowed.containsAll(options.keySet()))
            throw new IOException("Unsupported operation options.");
        Path output = root.resolve("output.pdf");
        if (operation.equals("cert-sign-invisible")) {
            if (options.has("tsaUrl")
                    || options.has("keyStoreType")
                    || !Set.of("PKCS12", "PFX").contains(text(options, "certType", "PKCS12")))
                throw new IOException("This signing mode is not connected.");
            Path certificate = root.resolve("certificate.p12");
            if (!Files.isRegularFile(certificate, LinkOption.NOFOLLOW_LINKS)
                    || !certificate.toRealPath().getParent().equals(root))
                throw new IOException("No selected certificate.");
            PdfCertificates.sign(
                    input,
                    output,
                    certificate,
                    text(options, "password", "").toCharArray(),
                    text(options, "reason", ""),
                    text(options, "location", ""),
                    text(options, "name", ""),
                    flag(options, "showSignature"),
                    options.has("pageNumber") ? options.get("pageNumber").getAsInt() : 1,
                    !options.has("showLogo") || flag(options, "showLogo"));
            return output;
        }
        if (operation.equals("signature-integrity")) {
            Path report = root.resolve("output.json");
            Path trust = root.resolve("trust.cer");
            if (Files.exists(trust, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(trust, LinkOption.NOFOLLOW_LINKS)
                            || !trust.toRealPath().getParent().equals(root)))
                throw new IOException("Invalid selected trust certificate.");
            Files.writeString(
                    report,
                    JSON.toJson(
                            PdfCertificates.integrity(input, Files.exists(trust) ? trust : null)));
            return report;
        }
        if (operation.equals("ocr")) return ocr(root, input, output, options);
        String credential = text(options, "sourcePassword", "");
        boolean selective =
                options.has("ownerPassword")
                        || options.has("sourcePassword")
                        || options.has("retainedOpeningPassword");
        if (operation.equals("decrypt")) {
            credential = text(options, "password", "");
            if (selective) {
                credential = text(options, "ownerPassword", "");
                if (credential.isEmpty())
                    credential = text(options, "sourcePassword", text(options, "password", ""));
            }
            if (flag(options, "removeAll"))
                credential = text(options, "sourcePassword", text(options, "password", ""));
        }
        if (operation.equals("image-pdf-create")) {
            try (var imageDocument = new PDDocument()) {
                PdfImageConversion.appendImages(
                        imageDocument,
                        attachmentGrants(root, options),
                        text(options, "fitOption", "fillPage"),
                        flag(options, "autoRotate"),
                        color(options));
                imageDocument.save(output.toFile());
                return output;
            }
        }
        if (operation.equals("portfolio-create")) {
            try (var portfolio =
                    new PdfPortfolio()
                            .createFiles(
                                    attachmentGrants(root, options),
                                    text(options, "coverTitle", ""))) {
                portfolio.save(output.toFile());
                return output;
            }
        }
        try (PDDocument document = Loader.loadPDF(input.toFile(), credential)) {
            if (Set.of("pdf-text-export", "pdf-image-export", "image-extract")
                    .contains(operation)) {
                if (document.isEncrypted()
                        && !document.getCurrentAccessPermission().canExtractContent())
                    throw new InspectionDenied();
                if (Files.size(input) > 100L * 1024 * 1024 || document.getNumberOfPages() > 10000)
                    throw new IOException("Export limits exceeded.");
                if (operation.equals("pdf-text-export")) {
                    if (!text(options, "outputFormat", "txt").equals("txt")
                            || flag(options, "useStirlingOfficeConvert"))
                        throw new IOException("Only local TXT export is connected.");
                    Path target = root.resolve("output.txt");
                    writeReport(target, PdfImageConversion.text(document));
                    return target;
                }
                if (operation.equals("image-extract")) {
                    String format = PdfImageConversion.format(text(options, "format", "png"));
                    Path target = root.resolve("output.zip");
                    try (var zip =
                            new ZipOutputStream(
                                    PdfImageConversion.bounded(
                                            Files.newOutputStream(
                                                    target,
                                                    java.nio.file.StandardOpenOption
                                                            .CREATE_NEW)))) {
                        var extractor = new PdfImageExtraction();
                        var fingerprints = new PdfImageExtraction.ImageFingerprints();
                        for (int page = 0; page < document.getNumberOfPages(); page++)
                            extractor.extractAndAddImagesToZip(
                                    document.getPage(page),
                                    format,
                                    filename(options),
                                    page + 1,
                                    fingerprints,
                                    zip);
                    }
                    return target;
                }
                int dpi = (int) number(options, "dpi", 300);
                if (dpi <= 0) dpi = 300;
                if (dpi > 500) throw new IOException("DPI exceeds500.");
                String selection = text(options, "pageNumbers", "all"),
                        mode = text(options, "singleOrMultiple", "multiple");
                if (selection.isBlank()) selection = "all";
                if (!Set.of("single", "multiple").contains(mode))
                    throw new IOException("Invalid image output mode.");
                var color =
                        switch (color(options)) {
                            case "greyscale" -> ImageType.GRAY;
                            case "blackwhite" -> ImageType.BINARY;
                            default -> ImageType.RGB;
                        };
                try (var selected = new PDDocument()) {
                    for (int page :
                            PdfPageSelection.parsePageList(
                                    selection, document.getNumberOfPages(), false)) {
                        if (page < 0 || page >= document.getNumberOfPages())
                            throw new IOException("Invalid selected page.");
                        selected.importPage(document.getPage(page));
                    }
                    Path target = root.resolve(mode.equals("single") ? "output.bin" : "output.zip");
                    Files.write(
                            target,
                            PdfImageConversion.render(
                                    selected,
                                    text(options, "imageFormat", "png"),
                                    color,
                                    mode.equals("single"),
                                    dpi,
                                    filename(options),
                                    flag(options, "includeAnnotations")),
                            java.nio.file.StandardOpenOption.CREATE_NEW);
                    return target;
                }
            }
            if (operation.equals("attachments")) {
                var attachments = new PdfAttachments(50L * 1024 * 1024, 200L * 1024 * 1024, true);
                String action = text(options, "action", "");
                if (Set.of("list", "extract", "single").contains(action)) {
                    if (document.isEncrypted()
                            && !document.getCurrentAccessPermission().canExtractContent())
                        throw new InspectionDenied();
                    if (action.equals("list")) {
                        Path json = root.resolve("output.json");
                        writeReport(json, JSON.toJson(attachments.listAttachments(document)));
                        return json;
                    }
                    byte[] bytes =
                            action.equals("single")
                                    ? attachments
                                            .extractSingleAttachment(
                                                    document, text(options, "attachmentName", ""))
                                            .orElseThrow()
                                    : attachments.extractAttachments(document).orElseThrow();
                    Path target =
                            root.resolve(action.equals("single") ? "output.bin" : "output.zip");
                    Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
                    return target;
                }
                PdfProtection.requireOwner(document);
                if (flag(options, "convertToPdfA3b")) throw new UnsupportedStructure();
                switch (action) {
                    case "add" -> attachments.addFiles(document, attachmentGrants(root, options));
                    case "rename" ->
                            attachments.renameAttachment(
                                    document,
                                    text(options, "attachmentName", ""),
                                    attachmentName(text(options, "newName", "")));
                    case "delete" ->
                            attachments.deleteAttachment(
                                    document, text(options, "attachmentName", ""));
                    case "batch" -> {
                        var ops = JSON.fromJson(text(options, "opsJson", "{}"), JsonObject.class);
                        if (ops.has("deletions"))
                            for (var item : ops.getAsJsonArray("deletions")) {
                                try {
                                    attachments.deleteAttachment(document, item.getAsString());
                                } catch (IOException | IllegalArgumentException missing) {
                                }
                            }
                        if (ops.has("renames"))
                            for (var item : ops.getAsJsonArray("renames")) {
                                var rename = item.getAsJsonObject();
                                if (rename.has("oldName") && rename.has("newName")) {
                                    try {
                                        attachments.renameAttachment(
                                                document,
                                                rename.get("oldName").getAsString(),
                                                attachmentName(
                                                        rename.get("newName").getAsString()));
                                    } catch (IOException | IllegalArgumentException missing) {
                                    }
                                }
                            }
                        if (options.has("selectedFiles")
                                && !options.getAsJsonArray("selectedFiles").isEmpty())
                            attachments.addFiles(document, attachmentGrants(root, options));
                    }
                    default -> throw new IOException("Unknown attachment action.");
                }
                document.save(output.toFile());
                return output;
            }
            if (operation.equals("portfolio-flatten")) {
                PdfProtection.requireOwner(document);
                var portfolio = new PdfPortfolio();
                if (!portfolio.isPortfolio(document)) throw new IOException("Not a portfolio.");
                portfolio.flattenPortfolio(document);
                document.save(output.toFile());
                return output;
            }
            if (operation.equals("document-inspect")) {
                String report = text(options, "report", "");
                if (document.getNumberOfPages() > 10000 || Files.size(input) > 100L * 1024 * 1024)
                    throw new IOException("Inspection input exceeds its resource limit.");
                if (document.isEncrypted()
                        && !document.getCurrentAccessPermission().canExtractContent()
                        && Set.of("all-info", "javascript").contains(report))
                    throw new InspectionDenied();
                Object result;
                switch (report) {
                    case "page-count" ->
                            result = PdfDocumentAnalysis.getPageCount(document, Files.size(input));
                    case "basic-info" ->
                            result = PdfDocumentAnalysis.getBasicInfo(document, Files.size(input));
                    case "document-properties" ->
                            result =
                                    PdfDocumentAnalysis.getDocumentProperties(
                                            document, Files.size(input));
                    case "page-dimensions" ->
                            result =
                                    PdfDocumentAnalysis.getPageDimensions(
                                            document, Files.size(input));
                    case "form-fields" ->
                            result = PdfDocumentAnalysis.getFormFields(document, Files.size(input));
                    case "annotation-info" ->
                            result =
                                    PdfDocumentAnalysis.getAnnotationInfo(
                                            document, Files.size(input));
                    case "font-info" ->
                            result = PdfDocumentAnalysis.getFontInfo(document, Files.size(input));
                    case "security-info" ->
                            result =
                                    PdfDocumentAnalysis.getSecurityInfo(
                                            document, Files.size(input));
                    case "javascript" -> {
                        Path text = root.resolve("output.txt");
                        writeReport(
                                text, PdfDocumentAnalysis.javascript(document, filename(options)));
                        return text;
                    }
                    case "verify-pdf" -> {
                        if (document.isEncrypted())
                            throw new IOException(
                                    "Standards verification requires an unencrypted copy.");
                        try (var bytes = Files.newInputStream(input)) {
                            result = new PdfStandards().validatePDF(bytes);
                        }
                    }
                    case "all-info" -> {
                        java.util.List<
                                        stirling.software
                                                .SPDF
                                                .model
                                                .api
                                                .security
                                                .PDFVerificationResult>
                                standards = null;
                        boolean validationFailed = false;
                        try (var bytes = Files.newInputStream(input)) {
                            standards = new PdfStandards().validatePDF(bytes);
                        } catch (Exception unavailable) {
                            validationFailed = true;
                        }
                        var full =
                                new PdfDocumentInfo()
                                        .inspect(document, Files.size(input), standards);
                        if (validationFailed)
                            ((tools.jackson.databind.node.ObjectNode) full.get("Compliancy"))
                                    .put(
                                            "ValidationError",
                                            "Standards validation could not run on these original bytes; no compliance verdict was fabricated.");
                        Path json = root.resolve("output.json");
                        writeReport(json, full.toString());
                        return json;
                    }
                    default -> throw new IOException("Unknown inspection report kind.");
                }
                Path json = root.resolve("output.json");
                writeReport(json, JSON.toJson(result));
                return json;
            }
            if (operation.equals("signature-remove")) {
                if (document.isEncrypted() && !PdfProtection.isOwnerAuthenticated(document))
                    throw new IOException("Owner authentication is required.");
                PdfCertificates.removeSignatures(document);
                document.save(output.toFile());
                return output;
            }
            PdfProtection.requireOwner(document);
            if (operation.equals("stamp")) {
                float size = number(options, "fontSize", 40),
                        opacity = number(options, "opacity", 0.5f);
                int position = (int) number(options, "position", 8);
                String type = text(options, "stampType", "text");
                if (!Set.of("text", "image", "qr").contains(type)
                        || size < 1
                        || size > 1000
                        || opacity < 0
                        || opacity > 1
                        || position < 1
                        || position > 9) throw new IOException("Invalid stamp options.");
                var pages =
                        selection(options, "pageNumbers", document).stream()
                                .map(i -> i + 1)
                                .toList();
                java.awt.image.BufferedImage bitmap = null;
                try {
                    if (type.equals("image")) {
                        var selected = root.resolve("image.bin");
                        if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)
                                || !selected.toRealPath().getParent().equals(root))
                            throw new IOException("No selected image grant.");
                        bitmap = PdfSelectedImage.decode(selected);
                    }
                    new PdfStamp()
                            .apply(
                                    document,
                                    pages,
                                    filename(options),
                                    type,
                                    text(options, "stampText", ""),
                                    bitmap,
                                    text(options, "alphabet", "roman"),
                                    size,
                                    number(options, "rotation", 0),
                                    opacity,
                                    position,
                                    number(options, "overrideX", -1),
                                    number(options, "overrideY", -1),
                                    text(options, "customColor", "#d3d3d3"),
                                    text(options, "customMargin", "medium"));
                } finally {
                    if (bitmap != null) bitmap.flush();
                }
                document.save(output.toFile());
                return output;
            }
            if (Set.of("page-crop", "page-scale", "page-numbers", "watermark")
                    .contains(operation)) {
                if (document.getNumberOfPages() > 10000)
                    throw new IOException("Document exceeds the formatting page limit.");
                if (operation.equals("page-crop") || operation.equals("page-scale")) {
                    var form = document.getDocumentCatalog().getAcroForm();
                    if (form != null && !form.getFields().isEmpty())
                        throw new UnsupportedStructure();
                    var outline = document.getDocumentCatalog().getDocumentOutline();
                    if (outline != null && outline.getFirstChild() != null)
                        throw new UnsupportedStructure();
                    for (var page : document.getPages())
                        for (var annotation : page.getAnnotations())
                            if (!operation.equals("page-scale")
                                    || !(annotation
                                            instanceof
                                            org.apache.pdfbox.pdmodel.interactive.annotation
                                                    .PDAnnotationRubberStamp)
                                    || annotation.isHidden()
                                    || annotation.isNoView()) throw new UnsupportedStructure();
                }
                if (operation.equals("page-crop") || operation.equals("page-scale")) {
                    try (var result = new PDDocument()) {
                        result.setDocumentInformation(document.getDocumentInformation());
                        if (operation.equals("page-scale")) {
                            float scale = number(options, "scaleFactor", 1);
                            if (scale <= 0 || scale > 100)
                                throw new IOException("Invalid scale factor.");
                            PdfPageScale.apply(
                                    document,
                                    result,
                                    text(options, "pageSize", "A4"),
                                    text(options, "orientation", "PORTRAIT"),
                                    scale);
                        } else {
                            if (flag(options, "removeDataOutsideCrop"))
                                throw new IOException(
                                        "Secure destructive crop requires an unavailable optional helper.");
                            var indices = selection(options, "pageNumbers", document);
                            var selected = new java.util.BitSet();
                            indices.forEach(selected::set);
                            if (flag(options, "autoCrop"))
                                PdfPageCrop.automatic(document, result, selected, 0, 0, 0, 0);
                            else {
                                float x = number(options, "x", 0),
                                        y = number(options, "y", 0),
                                        w = number(options, "width", 0),
                                        h = number(options, "height", 0);
                                if (w <= 0 || h <= 0) throw new IOException("Invalid crop bounds.");
                                for (int index : indices) {
                                    var box = document.getPage(index).getMediaBox();
                                    if (x < box.getLowerLeftX()
                                            || y < box.getLowerLeftY()
                                            || x + w > box.getUpperRightX()
                                            || y + h > box.getUpperRightY())
                                        throw new IOException(
                                                "Crop is outside selected page bounds.");
                                }
                                PdfPageCrop.manual(document, result, selected, x, y, w, h);
                            }
                        }
                        result.save(output.toFile());
                    }
                    return output;
                }
                if (operation.equals("page-numbers")) {
                    float size = number(options, "fontSize", 12);
                    int padding = (int) number(options, "zeroPad", 0);
                    if (size < 1 || size > 1000 || padding < 0 || padding > 100)
                        throw new IOException("Invalid page number typography.");
                    String filename = text(options, "filename", "document.pdf").replace('\\', '/');
                    filename = filename.substring(filename.lastIndexOf('/') + 1);
                    int dot = filename.lastIndexOf('.');
                    if (dot > 0) filename = filename.substring(0, dot);
                    PdfPageNumbers.apply(
                            document,
                            selection(options, "pagesToNumber", document),
                            text(options, "customMargin", "medium"),
                            (int) number(options, "position", 8),
                            (int) number(options, "startingNumber", 1),
                            text(options, "customText", "{n}"),
                            padding,
                            size,
                            text(options, "fontType", "helvetica"),
                            text(options, "fontColor", "#000000"),
                            filename);
                } else {
                    String type = text(options, "watermarkType", "text");
                    float size = number(options, "fontSize", 30),
                            opacity = number(options, "opacity", 0.5f);
                    int ws = (int) number(options, "widthSpacer", 50),
                            hs = (int) number(options, "heightSpacer", 50);
                    if (!Set.of("text", "image").contains(type)
                            || size < 1
                            || size > 1000
                            || opacity < 0
                            || opacity > 1
                            || ws < 0
                            || hs < 0) throw new IOException("Invalid watermark options.");
                    java.awt.image.BufferedImage image = null;
                    try {
                        if (type.equals("image")) {
                            var selected = root.resolve("image.bin");
                            if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)
                                    || !selected.toRealPath().getParent().equals(root))
                                throw new IOException("No selected bitmap grant.");
                            image = PdfSelectedImage.decode(selected);
                        }
                        PdfWatermarks.apply(
                                document,
                                type,
                                text(options, "watermarkText", ""),
                                image,
                                text(options, "alphabet", "roman"),
                                size,
                                number(options, "rotation", 0),
                                opacity,
                                ws,
                                hs,
                                text(options, "customColor", "#d3d3d3"));
                    } finally {
                        if (image != null) image.flush();
                    }
                    if (flag(options, "convertPDFToImage")) {
                        try (var raster = new PDDocument()) {
                            PdfRasterizer.append(document, raster);
                            raster.save(output.toFile());
                        }
                        return output;
                    }
                }
                document.save(output.toFile());
                return output;
            }
            switch (operation) {
                case "metadata-update" -> {
                    Map<String, String> custom = new HashMap<>();
                    if (options.has("allRequestParams")) {
                        var values = options.getAsJsonObject("allRequestParams");
                        for (var item : values.entrySet()) {
                            if (!item.getValue().isJsonPrimitive()
                                    || !item.getValue().getAsJsonPrimitive().isString())
                                throw new IOException("Custom metadata must contain strings.");
                            custom.put(item.getKey(), item.getValue().getAsString());
                        }
                    }
                    PdfMetadataEditor.update(
                            document,
                            flag(options, "deleteAll"),
                            text(options, "author", null),
                            text(options, "creationDate", null),
                            text(options, "creator", null),
                            text(options, "keywords", null),
                            text(options, "modificationDate", null),
                            text(options, "producer", null),
                            text(options, "subject", null),
                            text(options, "title", null),
                            text(options, "trapped", null),
                            custom);
                }
                case "forms-unlock" -> PdfForms.unlock(document);
                case "flatten" -> {
                    if (flag(options, "flattenOnlyForms")) {
                        var form = document.getDocumentCatalog().getAcroForm();
                        if (form != null && form.hasXFA())
                            throw new IOException(
                                    "XFA flattening is not connected; choose explicit full-page flattening.");
                        PdfForms.flatten(document);
                    } else {
                        int dpi =
                                options.has("renderDpi")
                                        ? Math.max(
                                                72,
                                                Math.min(300, options.get("renderDpi").getAsInt()))
                                        : 300;
                        try (PDDocument raster = new PDDocument()) {
                            raster.setDocumentInformation(document.getDocumentInformation());
                            PDFRenderer renderer = new PDFRenderer(document);
                            renderer.setSubsamplingAllowed(true);
                            for (int page = 0; page < document.getNumberOfPages(); page++) {
                                var box = document.getPage(page).getMediaBox();
                                double width = box.getWidth() / 72d * dpi,
                                        height = box.getHeight() / 72d * dpi;
                                if (!Double.isFinite(width * height)
                                        || width <= 0
                                        || height <= 0
                                        || width * height > 100_000_000d)
                                    throw new IOException(
                                            "The requested raster page exceeds the private worker pixel limit.");
                                var image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                                try {
                                    PdfPageFlattener.appendRenderedPage(
                                            raster, document.getPage(page), image);
                                } finally {
                                    image.flush();
                                }
                            }
                            raster.save(output.toFile());
                        }
                        return output;
                    }
                }
                case "sanitize" -> {
                    if (flag(options, "removeJavaScript"))
                        PdfSanitizer.sanitizeJavaScript(document);
                    if (flag(options, "removeEmbeddedFiles"))
                        PdfSanitizer.sanitizeEmbeddedFiles(document);
                    if (flag(options, "removeXMPMetadata"))
                        PdfSanitizer.sanitizeXMPMetadata(document);
                    if (flag(options, "removeMetadata"))
                        PdfSanitizer.sanitizeDocumentInfoMetadata(document);
                    if (flag(options, "removeLinks")) PdfSanitizer.sanitizeLinks(document);
                    if (flag(options, "removeFonts")) PdfSanitizer.sanitizeFonts(document);
                }
                case "aes-encrypt" -> {
                    var permissions = new AccessPermission();
                    permissions.setCanAssembleDocument(!flag(options, "preventAssembly"));
                    permissions.setCanExtractContent(!flag(options, "preventExtractContent"));
                    permissions.setCanExtractForAccessibility(
                            !flag(options, "preventExtractForAccessibility"));
                    permissions.setCanFillInForm(!flag(options, "preventFillInForm"));
                    permissions.setCanModify(!flag(options, "preventModify"));
                    permissions.setCanModifyAnnotations(!flag(options, "preventModifyAnnotations"));
                    permissions.setCanPrint(!flag(options, "preventPrinting"));
                    permissions.setCanPrintFaithful(!flag(options, "preventPrintingFaithful"));
                    PdfProtection.add(
                            document,
                            text(options, "ownerPassword", ""),
                            text(options, "password", ""),
                            credential,
                            options.has("keyLength") ? options.get("keyLength").getAsInt() : 256,
                            flag(options, "preserveExisting"),
                            permissions);
                }
                case "decrypt" ->
                        PdfProtection.remove(
                                document,
                                text(options, "password", ""),
                                text(options, "ownerPassword", ""),
                                text(options, "retainedOpeningPassword", ""),
                                credential,
                                selective,
                                flag(options, "removeAll"));
                default -> throw new IOException("Unknown operation.");
            }
            document.save(output.toFile());
            if (operation.equals("aes-encrypt")
                    && !flag(options, "preserveExisting")
                    && !new AccessPermission(document.getEncryption().getPermissions())
                            .isOwnerPermission()) {
                try (var verified =
                        Loader.loadPDF(output.toFile(), text(options, "password", ""))) {
                    if (PdfProtection.isOwnerAuthenticated(verified))
                        throw new IOException("Encoded passwords collapse into one role.");
                }
            }
        }
        return output;
    }

    private static final class UnsupportedStructure extends IOException {}

    private static final class InspectionDenied extends IOException {}

    private static void writeReport(Path output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32 * 1024 * 1024)
            throw new IOException("Report exceeds32MiB; no partial report was returned.");
        Files.write(output, bytes);
    }

    private static String filename(JsonObject options) {
        String name = text(options, "filename", "document.pdf").replace('\\', '/');
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private static float number(JsonObject options, String key, float fallback) throws IOException {
        float value = options.has(key) ? options.get(key).getAsFloat() : fallback;
        if (!Float.isFinite(value)) throw new IOException("Numeric options must be finite.");
        return value;
    }

    private static java.util.List<Integer> selection(
            JsonObject options, String key, PDDocument document) throws IOException {
        String input = text(options, key, "all");
        if (input.isBlank()) input = "all";
        var pages =
                PdfPageSelection.parsePageList(
                        input.split(","), document.getNumberOfPages(), false);
        if (pages.isEmpty())
            throw new IOException("The selection does not include a document page.");
        return pages;
    }

    private static Path helper() {
        String value = System.getenv("SOOTAN_OCRMYPDF");
        if (value == null) return null;
        Path path = Path.of(value);
        return path.isAbsolute() && Files.isRegularFile(path) ? path : null;
    }

    private static Path ocr(Path root, Path input, Path output, JsonObject options)
            throws Exception {
        if (!text(options, "languages", "eng").equals("eng"))
            throw new IOException("English OCR only.");
        if (flag(options, "clean")
                || flag(options, "cleanFinal")
                || flag(options, "removeImagesAfter"))
            throw new IOException("The selected optional OCR helper is not bundled.");
        try (var document = Loader.loadPDF(input.toFile())) {
            PdfProtection.requireOwner(document);
        }
        String renderer = text(options, "ocrRenderType", "auto");
        if (!Set.of("auto", "fpdf2", "sandwich", "hocr").contains(renderer))
            throw new IOException("Unsupported OCR renderer.");
        var command =
                new ArrayList<>(
                        List.of(
                                helper().toString(),
                                "--output-type",
                                "pdf",
                                "--pdf-renderer",
                                renderer,
                                "--language",
                                "eng"));
        if (flag(options, "deskew")) command.add("--deskew");
        if (flag(options, "rotatePages")) command.add("--rotate-pages");
        command.add(
                text(options, "ocrType", "skip-text").equals("force-ocr")
                        ? "--force-ocr"
                        : "--skip-text");
        Path sidecar = root.resolve("output.txt");
        if (flag(options, "sidecar")) {
            command.add("--sidecar");
            command.add(sidecar.toString());
        }
        command.add(input.toString());
        command.add(output.toString());
        Process child =
                new ProcessBuilder(command)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(root.resolve("helper.log").toFile())
                        .start();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    child.descendants().forEach(ProcessHandle::destroyForcibly);
                                    child.destroyForcibly();
                                }));
        if (child.waitFor() != 0) throw new IOException("OCR helper failed.");
        if (!flag(options, "sidecar")) return output;
        Path zip = root.resolve("output.zip");
        try (var stream = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path file : List.of(output, sidecar)) {
                stream.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, stream);
                stream.closeEntry();
            }
        }
        return zip;
    }

    private static void emit(Object value) {
        System.out.println(JSON.toJson(value));
        System.out.flush();
    }
}

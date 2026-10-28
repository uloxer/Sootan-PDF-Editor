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
import com.google.gson.JsonObject;

import stirling.software.pdf.PdfCertificates;
import stirling.software.pdf.PdfForms;
import stirling.software.pdf.PdfMetadataEditor;
import stirling.software.pdf.PdfPageFlattener;
import stirling.software.pdf.PdfProtection;
import stirling.software.pdf.PdfSanitizer;

/**
 * One private job per process. Stdio carries bounded JSON metadata; files stay inside its granted
 * job directory.
 */
public final class WorkerMain {
    private static final Gson JSON = new Gson();
    private static final Set<String> OPERATIONS =
            Set.of(
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
                            result.toString().endsWith(".zip")
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
                            "PROCESSING_FAILED",
                            "message",
                            "The selected operation could not complete. Check its options, document permissions and private helper availability."));
            System.exit(1);
        }
    }

    private static List<String> capabilities() {
        return OPERATIONS.stream()
                .filter(operation -> !operation.equals("ocr") || helper() != null)
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
        try (PDDocument document = Loader.loadPDF(input.toFile(), credential)) {
            if (operation.equals("signature-remove")) {
                if (document.isEncrypted() && !PdfProtection.isOwnerAuthenticated(document))
                    throw new IOException("Owner authentication is required.");
                PdfCertificates.removeSignatures(document);
                document.save(output.toFile());
                return output;
            }
            PdfProtection.requireOwner(document);
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

package stirling.software.pdf.json;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.SPDF.model.json.PdfJsonDocument;
import stirling.software.SPDF.model.json.PdfJsonStream;
import stirling.software.SPDF.service.PdfJsonCosMapper;
import stirling.software.SPDF.service.pdfjson.type3.Type3GlyphExtractor;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One-shot editable JSON processing; grants and local font directory are verified by the caller.
 */
public final class LocalPdfJson {
    private static final long BUDGET = 64L * 1024 * 1024;
    private static final Set<String> BINARY =
            Set.of(
                    "program",
                    "webProgram",
                    "pdfProgram",
                    "toUnicode",
                    "rawData",
                    "imageData",
                    "xmpMetadata",
                    "previewImage");
    private static final Set<String> ACTIONS =
            Set.of(
                    "JavaScript",
                    "Launch",
                    "URI",
                    "SubmitForm",
                    "ImportData",
                    "GoToR",
                    "GoToE",
                    "RichMediaExecute");
    private final Path root, fonts;
    private final String password;
    private final JsonMapper mapper =
            JsonMapper.builder(
                            JsonFactory.builder()
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(64)
                                                    .maxStringLength(24 * 1024 * 1024)
                                                    .maxTokenCount(1000000)
                                                    .maxDocumentLength(50L * 1024 * 1024)
                                                    .build())
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .build();

    public LocalPdfJson(Path root, Path fonts, String password) throws IOException {
        this.root = root.toRealPath();
        this.fonts = fonts.toRealPath();
        this.password = password;
    }

    private PdfJsonProcessor processor() {
        var context =
                new PdfJsonContext() {
                    public Configuration configuration() {
                        return new Configuration(false, 32L * 1024 * 1024, 0);
                    }

                    public Temp temp(String suffix) throws IOException {
                        Path path = Files.createTempFile(root, "json-", suffix);
                        return new Temp(
                                path,
                                () -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (IOException failure) {
                                        throw new UncheckedIOException(failure);
                                    }
                                });
                    }

                    public PDDocument load(Path path, boolean extraction) throws IOException {
                        return Loader.loadPDF(path.toFile(), password);
                    }

                    public PDDocument load(byte[] bytes, boolean extraction) throws IOException {
                        return Loader.loadPDF(bytes, password);
                    }

                    public String jobId() {
                        return null;
                    }

                    public boolean addNote(String id, String note) {
                        return false;
                    }

                    public boolean ghostscriptEnabled() {
                        return false;
                    }

                    public ProcessResult ghostscript(List<String> command) throws IOException {
                        throw new IOException("Ghostscript normalization is not bundled.");
                    }
                };
        var fallback =
                new PdfJsonFallbackFonts(
                        location ->
                                new PdfJsonContext.Resource() {
                                    private Path path() {
                                        if (!location.matches(
                                                "classpath:/static/fonts/[A-Za-z0-9._-]+"))
                                            throw new IllegalArgumentException(
                                                    "Invalid bundled font.");
                                        return fonts.resolve(
                                                location.substring(location.lastIndexOf('/') + 1));
                                    }

                                    public boolean exists() {
                                        return Files.isRegularFile(
                                                path(), LinkOption.NOFOLLOW_LINKS);
                                    }

                                    public InputStream getInputStream() throws IOException {
                                        var value = path();
                                        if (!value.toRealPath().getParent().equals(fonts)
                                                || Files.size(value) > 32L * 1024 * 1024)
                                            throw new IOException("Invalid bundled font.");
                                        return Files.newInputStream(value);
                                    }
                                },
                        () -> null);
        fallback.loadConfig();
        var checks = new PdfJsonFontChecks();
        var programs =
                new PdfJsonContext.FontPrograms() {
                    public byte[] convertCffProgramToTrueType(byte[] bytes, String unicode) {
                        return null;
                    }

                    public byte[] convertCffUsingFontForge(byte[] bytes) {
                        return null;
                    }

                    public boolean isCffConversionEnabled() {
                        return false;
                    }

                    public String getCffConverterMethod() {
                        return "unavailable";
                    }

                    public String detectTrueTypeFormat(byte[] bytes) {
                        return checks.detectTrueTypeFormat(bytes);
                    }

                    public String detectFontFlavor(byte[] bytes) {
                        return checks.detectFontFlavor(bytes);
                    }

                    public boolean isCffFontProgram(byte[] bytes) {
                        return checks.isCffFontProgram(bytes);
                    }
                };
        var processor =
                new PdfJsonProcessor(
                        context,
                        mapper,
                        new PdfJsonCosMapper(),
                        fallback,
                        programs,
                        request -> List.of(),
                        new Type3GlyphExtractor());
        processor.initializeToolAvailability();
        return processor;
    }

    private PdfJsonContext.Input input(Path path) {
        return new PdfJsonContext.Input() {
            public void transferTo(File target) throws IOException {
                try (var source = Files.newInputStream(path);
                        var output = Files.newOutputStream(target.toPath())) {
                    source.transferTo(output);
                }
            }

            public byte[] getBytes() throws IOException {
                return Files.readAllBytes(path);
            }
        };
    }

    public void export(Path input, Path output) throws IOException {
        if (Files.size(input) > 50L * 1024 * 1024)
            throw new IOException("JSON export exceeds input budget.");
        try (var document = Loader.loadPDF(input.toFile(), password)) {
            if (document.getNumberOfPages() > 100
                    || document.isEncrypted()
                            && !document.getCurrentAccessPermission().canExtractContent())
                throw new IOException(
                        "JSON export requires extraction permission and at most100 pages.");
            inspectPdf(document);
        }
        try (var target = bounded(output)) {
            processor().exportJson(input(input), false, target);
        }
    }

    public void rebuild(Path input, Path output) throws IOException {
        if (Files.size(input) > 50L * 1024 * 1024)
            throw new IOException("JSON exceeds input budget.");
        JsonNode tree = mapper.readTree(Files.readAllBytes(input));
        long[] budget = {0, 0};
        inspectJson(tree, 0, budget);
        if (!tree.isObject()
                || !tree.has("pages")
                || !tree.get("pages").isArray()
                || tree.get("pages").size() < 1
                || tree.get("pages").size() > 100)
            throw new IOException("Select an editable PDF JSON document with1to100 pages.");
        if (tree.has("fonts") && tree.get("fonts").size() > 512)
            throw new IOException("JSON font limit exceeded.");
        PdfJsonDocument model = mapper.treeToValue(tree, PdfJsonDocument.class);
        if (model.isLazyImages()) throw new IOException("Use a complete embedded JSON export.");
        try (var scratch = new PDDocument()) {
            var seen = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
            long[] streams = {0, 0};
            inspectModelStreams(tree, scratch, seen, streams);
        }
        try (var target = bounded(output)) {
            processor().convertJsonToPdf(model, target);
        }
        try (var verify = Loader.loadPDF(output.toFile())) {
            inspectPdf(verify);
        }
    }

    private OutputStream bounded(Path output) throws IOException {
        return new FilterOutputStream(Files.newOutputStream(output)) {
            long total;

            private void count(int n) throws IOException {
                total += n;
                if (total > 128L * 1024 * 1024)
                    throw new IOException("JSON conversion exceeds output budget.");
            }

            public void write(int b) throws IOException {
                count(1);
                out.write(b);
            }

            public void write(byte[] b, int offset, int length) throws IOException {
                count(length);
                out.write(b, offset, length);
            }
        };
    }

    private void inspectJson(JsonNode node, int depth, long[] budget) throws IOException {
        if (depth > 64 || ++budget[0] > 100000)
            throw new IOException("JSON structure exceeds limits.");
        if (node.isObject()) {
            if (node.has("type")
                    && "NAME".equals(node.get("type").asText())
                    && node.has("value")
                    && ACTIONS.contains(node.get("value").asText()))
                throw new IOException("Active PDF actions are not accepted in JSON.");
            for (var entry : node.properties()) {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (value.isTextual()
                        && (BINARY.contains(key)
                                || key.equals("value")
                                        && node.has("type")
                                        && "STRING".equals(node.get("type").asText()))) {
                    String encoded = value.asText();
                    if (encoded.length() > 24 * 1024 * 1024)
                        throw new IOException("JSON binary field exceeds limits.");
                    byte[] bytes;
                    try {
                        bytes = Base64.getDecoder().decode(encoded);
                    } catch (IllegalArgumentException invalid) {
                        throw new IOException("Invalid JSON binary field.");
                    }
                    budget[1] += bytes.length;
                    if (bytes.length > 16 * 1024 * 1024 || budget[1] > BUDGET)
                        throw new IOException("JSON binary budget exceeded.");
                    if (key.equals("xmpMetadata")) {
                        String xml =
                                new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                                        .toUpperCase(Locale.ROOT);
                        if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY"))
                            throw new IOException("External XML entities are unavailable.");
                    }
                    if (key.equals("imageData") || key.equals("previewImage")) inspectImage(bytes);
                }
                inspectJson(value, depth + 1, budget);
            }
        } else if (node.isArray())
            for (JsonNode child : node) inspectJson(child, depth + 1, budget);
    }

    private void inspectImage(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported JSON bitmap.");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!Set.of("PNG", "JPEG", "JPG", "GIF", "BMP", "TIFF", "TIF")
                                .contains(reader.getFormatName().toUpperCase(Locale.ROOT))
                        || (long) reader.getWidth(0) * reader.getHeight(0) > 25000000)
                    throw new IOException("JSON bitmap exceeds limits.");
            } finally {
                reader.dispose();
            }
        }
    }

    private void inspectModelStreams(
            JsonNode node, PDDocument document, Set<COSBase> seen, long[] budget)
            throws IOException {
        if (node.isObject()) {
            if (node.has("rawData") && node.has("dictionary")) {
                var stream = mapper.treeToValue(node, PdfJsonStream.class);
                inspectCos(
                        new PdfJsonCosMapper().buildStreamFromModel(stream, document),
                        0,
                        seen,
                        budget);
                return;
            }
            for (var entry : node.properties())
                inspectModelStreams(entry.getValue(), document, seen, budget);
        } else if (node.isArray())
            for (JsonNode child : node) inspectModelStreams(child, document, seen, budget);
    }

    private static void inspectPdf(PDDocument document) throws IOException {
        Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        long[] budget = {0, 0};
        inspectCos(document.getDocumentCatalog().getCOSObject(), 0, seen, budget);
    }

    private static void inspectCos(COSBase node, int depth, Set<COSBase> seen, long[] budget)
            throws IOException {
        if (node == null || !seen.add(node)) return;
        if (depth > 64 || ++budget[0] > 100000)
            throw new IOException("PDF object budget exceeded.");
        if (node instanceof COSObject object) {
            inspectCos(object.getObject(), depth + 1, seen, budget);
            return;
        }
        if (node instanceof COSStream stream) {
            int width = stream.getInt(COSName.WIDTH, 0), height = stream.getInt(COSName.HEIGHT, 0);
            if ((long) width * height > 25000000)
                throw new IOException("PDF bitmap exceeds limits.");
            try (var input = stream.createInputStream()) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int size;
                while ((size = input.read(buffer)) != -1) {
                    total += size;
                    budget[1] += size;
                    if (total > 16L * 1024 * 1024 || budget[1] > BUDGET)
                        throw new IOException("PDF decoded stream budget exceeded.");
                }
            }
        }
        if (node instanceof COSDictionary dictionary)
            for (COSName key : dictionary.keySet())
                inspectCos(dictionary.getItem(key), depth + 1, seen, budget);
        else if (node instanceof COSArray array)
            for (COSBase child : array) inspectCos(child, depth + 1, seen, budget);
    }
}

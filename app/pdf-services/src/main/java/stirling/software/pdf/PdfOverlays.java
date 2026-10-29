package stirling.software.pdf;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.multipdf.Overlay;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Adds visual overlay pages without changing base page geometry/catalog structures. Caller owns
 * inputs.
 */
public final class PdfOverlays {
    public static final class SourceUnsupported extends IOException {
        public SourceUnsupported() {
            super(
                    "An overlay source requires readable page content without visible annotations/forms.");
        }
    }

    private record Choice(int file, int page) {}

    public static void apply(
            PDDocument base,
            List<PDDocument> sources,
            String mode,
            int[] counts,
            int position,
            Path output)
            throws IOException {
        PdfProtection.requireOwner(base);
        if (base.getNumberOfPages() < 1
                || base.getNumberOfPages() > 1000
                || sources.isEmpty()
                || sources.size() > 100
                || position < 0
                || position > 1)
            throw new IllegalArgumentException(
                    "Select 1–100 overlay PDFs, a 1–1000 page base and foreground/background position.");
        for (var source : sources) {
            if (source.getNumberOfPages() < 1
                    || source.getNumberOfPages() > 1000
                    || source.isEncrypted()
                            && !source.getCurrentAccessPermission().canExtractContent())
                throw new SourceUnsupported();
        }
        List<Choice> choices = choices(base.getNumberOfPages(), sources, mode, counts);
        Map<Choice, PDDocument> normalized = new HashMap<>();
        try (Overlay overlay =
                new Overlay() {
                    @Override
                    protected AffineTransform calculateAffineTransform(
                            PDPage page, PDRectangle box) {
                        try {
                            var transform = PdfLayouts.displayTransform(page).createInverse();
                            var visible = PdfLayouts.displayedSize(page);
                            transform.translate(
                                    (visible.getWidth() - box.getWidth()) / 2,
                                    (visible.getHeight() - box.getHeight()) / 2);
                            return transform;
                        } catch (IOException | NoninvertibleTransformException failure) {
                            throw new IllegalArgumentException(
                                    "Unsupported overlay page geometry.");
                        }
                    }
                }) {
            Map<Integer, PDDocument> guide = new HashMap<>();
            for (int i = 0; i < choices.size(); i++) {
                Choice choice = choices.get(i);
                if (!normalized.containsKey(choice)) {
                    var source = sources.get(choice.file());
                    var page = source.getPage(choice.page());
                    if (!page.getAnnotations().isEmpty()) throw new SourceUnsupported();
                    PDDocument single = new PDDocument();
                    normalized.put(choice, single);
                    var target = new PDPage(PdfLayouts.displayedSize(page));
                    single.addPage(target);
                    try (var content = new PDPageContentStream(single, target)) {
                        content.drawForm(
                                PdfLayouts.displayForm(
                                        source, choice.page(), new LayerUtility(single)));
                    }
                }
                guide.put(i + 1, normalized.get(choice));
            }
            overlay.setInputPDF(base);
            overlay.setOverlayPosition(
                    position == 0 ? Overlay.Position.FOREGROUND : Overlay.Position.BACKGROUND);
            // Shared source streams must stay alive through serialization (PDFBOX-6203).
            try (var stream = PdfImageConversion.bounded(Files.newOutputStream(output))) {
                overlay.overlayDocuments(guide).save(stream, CompressParameters.NO_COMPRESSION);
            }
        } finally {
            for (var document : normalized.values()) document.close();
        }
    }

    private static List<Choice> choices(
            int total, List<PDDocument> sources, String mode, int[] counts) {
        List<Choice> result = new ArrayList<>();
        if (mode == null) throw new IllegalArgumentException("Select an overlay mode.");
        switch (mode) {
            case "SequentialOverlay" -> {
                int file = 0, page = 0;
                for (int i = 0; i < total; i++) {
                    result.add(new Choice(file, page++));
                    if (page == sources.get(file).getNumberOfPages()) {
                        page = 0;
                        file = (file + 1) % sources.size();
                    }
                }
            }
            case "InterleavedOverlay" -> {
                int[] pages = new int[sources.size()];
                for (int i = 0; i < total; i++) {
                    int file = i % sources.size();
                    result.add(
                            new Choice(file, pages[file]++ % sources.get(file).getNumberOfPages()));
                }
            }
            case "FixedRepeatOverlay" -> {
                if (counts == null || counts.length != sources.size())
                    throw new IllegalArgumentException("Counts must match overlay files.");
                for (int file = 0; file < sources.size(); file++) {
                    if (counts[file] < 0)
                        throw new IllegalArgumentException("Overlay counts cannot be negative.");
                    for (int repeat = 0; repeat < counts[file] && result.size() < total; repeat++)
                        for (int page = 0;
                                page < sources.get(file).getNumberOfPages()
                                        && result.size() < total;
                                page++) result.add(new Choice(file, page));
                }
            }
            default -> throw new IllegalArgumentException("Unknown overlay mode.");
        }
        return result;
    }
}

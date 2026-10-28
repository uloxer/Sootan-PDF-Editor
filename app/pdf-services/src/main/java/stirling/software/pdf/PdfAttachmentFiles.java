package stirling.software.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PageMode;

/** Selected attachment streams are owned by the caller and opened only during embedding. */
public final class PdfAttachmentFiles {
    private PdfAttachmentFiles() {}

    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    public record SelectedFile(String name, String contentType, long size, Opener opener) {
        public InputStream open() throws IOException {
            return opener.open();
        }
    }

    public static void validate(List<SelectedFile> files) throws IOException {
        if (files.isEmpty() || files.size() > 100) throw new IOException("Select one to100 files.");
        long total = 0;
        for (var file : files) {
            if (file.size() <= 0
                    || file.size() > 50L * 1024 * 1024
                    || file.name() == null
                    || file.name().isBlank()
                    || file.name().length() > 1024)
                throw new IOException("Invalid selected attachment.");
            total += file.size();
        }
        if (total > 200L * 1024 * 1024) throw new IOException("Attachments exceed200MiB.");
    }

    static IllegalArgumentException argument(String key, String message, Object... args) {
        return new IllegalArgumentException(java.text.MessageFormat.format(message, args));
    }

    static void setCatalogViewerPreferences(PDDocument document, PageMode mode) {
        var catalog = document.getDocumentCatalog();
        catalog.setPageMode(mode);
        var dictionary = catalog.getCOSObject();
        var preferences = dictionary.getCOSDictionary(COSName.VIEWER_PREFERENCES);
        if (preferences == null) {
            preferences = new COSDictionary();
            dictionary.setItem(COSName.VIEWER_PREFERENCES, preferences);
        }
        preferences.setName(COSName.getPDFName("NonFullScreenPageMode"), mode.stringValue());
        preferences.setBoolean(COSName.getPDFName("DisplayDocTitle"), true);
    }
}

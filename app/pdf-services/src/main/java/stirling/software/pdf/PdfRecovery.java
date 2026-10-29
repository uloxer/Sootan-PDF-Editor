package stirling.software.pdf;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;

import lombok.extern.slf4j.Slf4j;

/** PDFBox fallback repair and stream decompression; recovery never promises missing content. */
@Slf4j
public final class PdfRecovery {
    public static void decompress(PDDocument document) throws IOException {
        PdfProtection.requireOwner(document);
        processAllObjects(document);
    }

    public static void repair(PDDocument document, Path output) throws IOException {
        PdfProtection.requireOwner(document);
        try (var stream = PdfImageConversion.bounded(Files.newOutputStream(output))) {
            document.save(stream);
        }
    }

    private static void processAllObjects(PDDocument document) {
        Set<COSBase> processed = new HashSet<>();
        COSDocument cosDoc = document.getDocument();

        // Process all objects in the document
        for (COSObjectKey key : cosDoc.getXrefTable().keySet()) {
            COSObject obj = cosDoc.getObjectFromPool(key);
            processObject(obj, processed);
        }
    }

    private static void processObject(COSBase obj, Set<COSBase> processed) {
        // Skip null objects or already processed objects to avoid infinite recursion
        if (obj == null || processed.contains(obj)) return;
        processed.add(obj);

        if (obj instanceof COSObject cosObj) {
            processObject(cosObj.getObject(), processed);
        } else if (obj instanceof COSDictionary dict) {
            processDictionary(dict, processed);
        } else if (obj instanceof COSArray array) {
            processArray(array, processed);
        }
    }

    private static void processDictionary(COSDictionary dict, Set<COSBase> processed) {
        // Process all dictionary entries
        for (COSName key : dict.keySet()) {
            processObject(dict.getDictionaryObject(key), processed);
        }

        // If this is a stream, decompress it
        if (dict instanceof COSStream stream) {
            decompressStream(stream);
        }
    }

    private static void processArray(COSArray array, Set<COSBase> processed) {
        // Process all array elements
        for (int i = 0; i < array.size(); i++) {
            processObject(array.get(i), processed);
        }
    }

    private static void decompressStream(COSStream stream) {
        try {

            // Only remove filter information if it exists
            if (stream.containsKey(COSName.FILTER)
                    || stream.containsKey(COSName.DECODE_PARMS)
                    || stream.containsKey(COSName.D)) {

                // Read the decompressed content first
                byte[] decompressedBytes;
                try (COSInputStream is = stream.createInputStream()) {
                    decompressedBytes = is.readNBytes(100 * 1024 * 1024 + 1);
                }

                if (decompressedBytes.length > 100 * 1024 * 1024)
                    throw new IOException("Decoded stream exceeds100MiB.");
                // Now remove filter information
                stream.removeItem(COSName.FILTER);
                stream.removeItem(COSName.DECODE_PARMS);
                stream.removeItem(COSName.D);

                // Write the raw content back
                try (OutputStream out = stream.createRawOutputStream()) {
                    out.write(decompressedBytes);
                }

                // Set the Length to reflect the new stream size
                stream.setInt(COSName.LENGTH, decompressedBytes.length);
            }
        } catch (IOException e) {
            log.warn("A stream could not be decompressed; it remains encoded.");
            // Continue processing other streams even if this one fails
        }
    }
}

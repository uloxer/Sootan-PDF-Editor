package stirling.software.pdf;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;

/** Metadata mutations shared by HTTP and private non-web callers. */
public final class PdfMetadataEditor {
    private static final Pattern NUMERIC = Pattern.compile("[^0-9]");

    private PdfMetadataEditor() {}

    /**
     * Mutates only metadata; omitted standard values clear their fields, matching the editor tool.
     */
    public static void update(
            PDDocument document,
            boolean deleteAll,
            String author,
            String creationDate,
            String creator,
            String keywords,
            String modificationDate,
            String producer,
            String subject,
            String title,
            String trapped,
            Map<String, String> allRequestParams) {
        if (allRequestParams == null) allRequestParams = Map.of();
        PDDocumentInformation info = document.getDocumentInformation();

        author = undefined(author);
        creationDate = undefined(creationDate);
        creator = undefined(creator);
        keywords = undefined(keywords);
        modificationDate = undefined(modificationDate);
        producer = undefined(producer);
        subject = undefined(subject);
        title = undefined(title);
        trapped = undefined(trapped);

        if (deleteAll) {
            for (String key : info.getMetadataKeys()) {
                info.setCustomMetadataValue(key, null);
            }
            document.getDocumentCatalog().getCOSObject().removeItem(COSName.getPDFName("Metadata"));
            document.getDocumentCatalog()
                    .getCOSObject()
                    .removeItem(COSName.getPDFName("PieceInfo"));
            author = null;
            creationDate = null;
            creator = null;
            keywords = null;
            modificationDate = null;
            producer = null;
            subject = null;
            title = null;
            trapped = null;
        } else {
            for (Entry<String, String> entry : allRequestParams.entrySet()) {
                String key = entry.getKey();
                if (!"Author".equalsIgnoreCase(key)
                        && !"CreationDate".equalsIgnoreCase(key)
                        && !"Creator".equalsIgnoreCase(key)
                        && !"Keywords".equalsIgnoreCase(key)
                        && !"modificationDate".equalsIgnoreCase(key)
                        && !"Producer".equalsIgnoreCase(key)
                        && !"Subject".equalsIgnoreCase(key)
                        && !"Title".equalsIgnoreCase(key)
                        && !"Trapped".equalsIgnoreCase(key)
                        && !key.contains("customKey")
                        && !key.contains("customValue")) {
                    info.setCustomMetadataValue(key, entry.getValue());
                } else if (key.contains("customKey")) {
                    try {
                        int number = Integer.parseInt(NUMERIC.matcher(key).replaceAll(""));
                        String customKey = entry.getValue();
                        String customValue = allRequestParams.get("customValue" + number);
                        info.setCustomMetadataValue(customKey, customValue);
                    } catch (NumberFormatException ignored) {
                        continue;
                    }
                }
            }
        }
        Calendar creationDateCal = parseDate(creationDate);
        info.setCreationDate(creationDateCal);

        Calendar modificationDateCal = parseDate(modificationDate);
        info.setModificationDate(modificationDateCal);
        info.setCreator(creator);
        info.setKeywords(keywords);
        info.setAuthor(author);
        info.setProducer(producer);
        info.setSubject(subject);
        info.setTitle(title);
        info.setTrapped(trapped);

        document.setDocumentInformation(info);
    }

    private static String undefined(String value) {
        return "undefined".equals(value) ? null : value;
    }

    private static Calendar parseDate(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            var time =
                    LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
                            .atZone(ZoneId.systemDefault());
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(time.toInstant().toEpochMilli());
            return calendar;
        } catch (Exception invalid) {
            return null;
        }
    }
}

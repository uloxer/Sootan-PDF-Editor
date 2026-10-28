package stirling.software.pdf;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.OfficeConvert;
import stirling.software.officeconvert.PdfToPptx;
import stirling.software.officeconvert.legacy.PdfToPpt;

/** Existing in-process editable Office export algorithms and their output-format contract. */
public class PdfOfficeConversion {
    private static final Map<String, OfficeConvert.Format> FORMATS =
            Map.of(
                    "docx", OfficeConvert.Format.DOCX,
                    "doc", OfficeConvert.Format.RTF,
                    "odt", OfficeConvert.Format.ODT,
                    "rtf", OfficeConvert.Format.RTF,
                    "txt", OfficeConvert.Format.TXT,
                    "pptx", OfficeConvert.Format.PPTX,
                    "odp", OfficeConvert.Format.ODP,
                    "xlsx", OfficeConvert.Format.XLSX,
                    "ods", OfficeConvert.Format.ODS);

    public static boolean supports(String format) {
        return FORMATS.containsKey(format) || "ppt".equals(format);
    }

    public void convert(
            PDDocument document, Path target, String format, OfficeConvert.Settings settings)
            throws IOException {
        if (!supports(format)) {
            throw new IllegalArgumentException("No PDF to Office conversion to " + format);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            if ("ppt".equals(format)) {
                // Binary PowerPoint comes from the legacy module, outside OfficeConvert's timeout.
                PdfToPpt.convert(document, out, slides(settings));
            } else {
                OfficeConvert.convert(document, out, FORMATS.get(format), settings);
            }
        }
    }

    /**
     * Whether a workbook holds any cell. With no table found, a sheet-per-table workbook is a
     * single empty sheet.
     */
    public static boolean hasCells(Path xlsx) throws IOException {
        try (ZipFile zip = new ZipFile(xlsx.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith("xl/worksheets/")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    // A sheet's first cell follows its short header, well within the first
                    // megabyte.
                    if (new String(in.readNBytes(1 << 20), StandardCharsets.ISO_8859_1)
                            .contains("<c ")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static PdfToPptx.Options slides(OfficeConvert.Settings s) {
        return new PdfToPptx.Options(
                s.firstPage(),
                s.lastPage(),
                s.tables(),
                s.figureDpi(),
                s.password(),
                s.pictureFallback(),
                s.pictures());
    }
}

package stirling.software.pdf;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.common.util.CsvSanitizer;

/** Extracts ruled tables with the same lattice parser and literal-cell policy as the CSV tool. */
public final class PdfCsvExtraction {
    public record Entry(int page, int table, String content) {}

    public static List<Entry> extract(PDDocument document, List<Integer> oneBasedPages)
            throws IOException {
        return extract(document, oneBasedPages, new TabulaTableParser());
    }

    public static List<Entry> extract(
            PDDocument document, List<Integer> oneBasedPages, TabulaTableParser parser)
            throws IOException {
        var entries = new ArrayList<Entry>();
        var format = CSVFormat.EXCEL.builder().setEscape('"').setQuoteMode(QuoteMode.ALL).build();
        long characters = 0;
        for (int page : oneBasedPages) {
            if (page < 1 || page > document.getNumberOfPages())
                throw new IOException("Invalid table page.");
            var tables = parser.parse(document, page);
            for (int index = 0; index < tables.size(); index++) {
                var buffer = new StringWriter();
                try (CSVPrinter printer = format.print(buffer)) {
                    for (var row : tables.get(index).rawRows())
                        printer.printRecord(CsvSanitizer.sanitizeRow(row));
                }
                characters += buffer.getBuffer().length();
                if (characters > 64L * 1024 * 1024 || entries.size() >= 1000)
                    throw new IOException("Table export exceeds limits.");
                entries.add(new Entry(page, index + 1, buffer.toString()));
            }
        }
        return entries;
    }
}

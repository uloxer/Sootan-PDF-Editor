package stirling.software.pdf;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.transform.*;

import org.w3c.dom.*;

import stirling.software.common.util.ZipBombGuard;
import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.io.PoiXml;

/** In-process imports use validated private files; XML never resolves external resources. */
public final class PdfOfficeInput {
    private PdfOfficeInput() {}

    public static OfficeToPdf.Result convert(Path in, Path out, OfficeToPdf.Options options)
            throws IOException {
        PoiXml.raiseProcessLimits();
        return OfficeToPdf.convert(in, out, options);
    }

    public static Path stage(Path input, Path job, String extension) throws Exception {
        if (!Set.of("docx", "pptx", "xlsx", "ppt", "txt", "csv", "tsv").contains(extension)
                || Files.size(input) > 50L * 1024 * 1024)
            throw new IOException("Unsupported local Office input.");
        Path target = job.resolve("office-input." + extension);
        byte[] magic;
        try (var stream = Files.newInputStream(input)) {
            magic = stream.readNBytes(8);
        }
        if (Set.of("docx", "pptx", "xlsx").contains(extension)) {
            if (magic.length < 4
                    || magic[0] != 'P'
                    || magic[1] != 'K'
                    || magic[2] != 3
                    || magic[3] != 4) throw new IOException("Office archive signature mismatch.");
            var names = new HashSet<String>();
            var budget = new ZipBombGuard.Budget(10L * 1024 * 1024, 200L * 1024 * 1024, 5000);
            try (var zip = new ZipInputStream(Files.newInputStream(input));
                    var output =
                            new ZipOutputStream(
                                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.startsWith("/")
                            || name.contains("\\")
                            || name.matches("^[A-Za-z]:.*")
                            || Arrays.asList(name.split("/", -1)).contains("..")
                            || !names.add(name))
                        throw new IOException("Invalid Office archive entry.");
                    byte[] bytes = budget.readEntry(zip);
                    if (name.endsWith(".xml") || name.endsWith(".rels"))
                        bytes = secureXml(bytes, name.endsWith(".rels"));
                    output.putNextEntry(new ZipEntry(name));
                    output.write(bytes);
                    output.closeEntry();
                }
            }
            String main =
                    extension.equals("docx")
                            ? "word/document.xml"
                            : extension.equals("xlsx") ? "xl/workbook.xml" : "ppt/presentation.xml";
            if (!names.contains("[Content_Types].xml") || !names.contains(main))
                throw new IOException("Office package does not match its selected format.");
        } else {
            if (extension.equals("ppt")
                    && !Arrays.equals(
                            magic,
                            new byte[] {
                                (byte) 0xd0,
                                (byte) 0xcf,
                                0x11,
                                (byte) 0xe0,
                                (byte) 0xa1,
                                (byte) 0xb1,
                                0x1a,
                                (byte) 0xe1
                            })) throw new IOException("Binary PowerPoint signature mismatch.");
            if (!extension.equals("ppt")
                    && (magic.length == 0
                            || magic[0] == 0
                            || magic[0] == 'P' && magic.length > 1 && magic[1] == 'K'))
                throw new IOException("Expected plain text input.");
            Files.copy(input, target);
        }
        return target;
    }

    public static final class ExternalResource extends IOException {}

    private static byte[] secureXml(byte[] bytes, boolean relationships) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        if (!relationships) return bytes;
        var refs = document.getElementsByTagNameNS("*", "Relationship");
        for (int i = refs.getLength() - 1; i >= 0; i--) {
            var ref = (Element) refs.item(i);
            if ("External".equalsIgnoreCase(ref.getAttribute("TargetMode"))) {
                throw new ExternalResource();
            }
        }
        return bytes;
    }
}

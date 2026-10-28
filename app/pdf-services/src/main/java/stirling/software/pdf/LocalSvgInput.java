package stirling.software.pdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Offline SVG preflight: only fragment references and bounded embedded bitmap images are allowed.
 */
public final class LocalSvgInput {
    private static final Pattern URL =
            Pattern.compile("url\\s*\\((.*?)\\)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    public static byte[] sanitize(byte[] input) throws Exception {
        if (input.length == 0 || input.length > 8 * 1024 * 1024)
            throw new IOException("SVG exceeds limits.");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", 128);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(input));
        var root = doc.getDocumentElement();
        if (!"svg".equals(root.getLocalName())
                || !"http://www.w3.org/2000/svg".equals(root.getNamespaceURI()))
            throw new IOException("Select an SVG document.");
        inspect(doc, new int[] {0}, new long[] {0});
        return new SvgContentSanitizer(url -> false, () -> false).sanitize(input);
    }

    private static void inspect(Node node, int[] count, long[] pixels) throws IOException {
        if (++count[0] > 50000) throw new IOException("SVG exceeds node limits.");
        if (node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE)
            throw new IOException("SVG processing instructions are unavailable.");
        if (node instanceof Element element) {
            if ("style".equalsIgnoreCase(element.getLocalName())) css(element.getTextContent());
            var attrs = element.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                var attr = attrs.item(i);
                String name =
                        attr.getLocalName() == null ? attr.getNodeName() : attr.getLocalName();
                String value = attr.getNodeValue().trim();
                if ("base".equals(name)
                        && "http://www.w3.org/XML/1998/namespace".equals(attr.getNamespaceURI()))
                    throw new IOException("SVG external base URIs are unavailable.");
                if (name.equalsIgnoreCase("href") || name.equalsIgnoreCase("src")) {
                    if (!value.isEmpty() && !value.startsWith("#")) {
                        pixels[0] += bitmap(value);
                        if (pixels[0] > 25000000)
                            throw new IOException(
                                    "SVG embedded images exceed the aggregate pixel limit.");
                    }
                } else if (!name.startsWith("xmlns")) css(value);
            }
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
            inspect(child, count, pixels);
    }

    private static void css(String text) throws IOException {
        String normalized = text.replaceAll("(?s)/\\*.*?\\*/", "").toLowerCase(Locale.ROOT);
        if (normalized.contains("\\")
                || normalized.contains("@import")
                || normalized.contains("@font-face"))
            throw new IOException("External SVG styles or fonts are unavailable.");
        var matcher = URL.matcher(normalized);
        while (matcher.find()) {
            String target = matcher.group(1).trim().replace("\"", "").replace("'", "");
            if (!target.startsWith("#"))
                throw new IOException("External SVG resources are unavailable.");
        }
    }

    private static long bitmap(String value) throws IOException {
        if (!value.matches("(?is)data:image/(png|jpeg|gif);base64,[a-z0-9+/=\\s]+"))
            throw new IOException("SVG requires a fragment or embedded bitmap.");
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(value.substring(value.indexOf(',') + 1));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid embedded bitmap.");
        }
        if (bytes.length > 4 * 1024 * 1024)
            throw new IOException("Embedded bitmap exceeds limits.");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid embedded bitmap.");
            var reader = readers.next();
            try {
                reader.setInput(input);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels < 1 || pixels > 25000000)
                    throw new IOException("Embedded bitmap exceeds pixel limits.");
                return pixels;
            } finally {
                reader.dispose();
            }
        }
    }
}

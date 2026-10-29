package stirling.software.pdf.json;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PdfJsonFontChecks {
    public String detectFontFlavor(byte[] fontBytes) {
        if (fontBytes == null || fontBytes.length < 4) {
            return null;
        }
        int signature =
                ((fontBytes[0] & 0xFF) << 24)
                        | ((fontBytes[1] & 0xFF) << 16)
                        | ((fontBytes[2] & 0xFF) << 8)
                        | (fontBytes[3] & 0xFF);
        if (signature == 0x00010000 || signature == 0x74727565) {
            return "ttf";
        }
        if (signature == 0x4F54544F) {
            return "otf";
        }
        if (signature == 0x74746366) {
            log.debug("[FONT-DEBUG] TrueType Collection ('ttcf') font program is unsupported");
            return null;
        }
        return null;
    }

    public String detectTrueTypeFormat(byte[] data) {
        if (data == null || data.length < 4) {
            return null;
        }
        int signature =
                ((data[0] & 0xFF) << 24)
                        | ((data[1] & 0xFF) << 16)
                        | ((data[2] & 0xFF) << 8)
                        | (data[3] & 0xFF);
        if (signature == 0x00010000) {
            return "ttf";
        }
        if (signature == 0x4F54544F) {
            return "otf";
        }
        if (signature == 0x74746366) {
            log.debug("[FONT-DEBUG] TrueType Collection ('ttcf') FontFile2 is unsupported");
            return null;
        }
        return null;
    }

    public String validateFontTables(byte[] fontBytes) {
        if (fontBytes == null || fontBytes.length < 12) {
            return "Font program too small";
        }
        int numTables = ((fontBytes[4] & 0xFF) << 8) | (fontBytes[5] & 0xFF);
        if (numTables <= 0 || numTables > 512) {
            return "Invalid numTables: " + numTables;
        }
        return null;
    }

    public boolean isCffFontProgram(byte[] fontBytes) {
        if (fontBytes == null || fontBytes.length < 4) {
            return false;
        }
        int majorVersion = fontBytes[0] & 0xFF;
        int headerSize = fontBytes[2] & 0xFF;
        int offsetSize = fontBytes[3] & 0xFF;
        return majorVersion == 1
                && headerSize >= 4
                && headerSize <= fontBytes.length
                && offsetSize >= 1
                && offsetSize <= 4;
    }
}

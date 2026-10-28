package stirling.software.pdf;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;

import javax.imageio.ImageIO;

/** Bounded bitmap decoder; selected bytes never choose a decoder URL or filesystem path. */
public final class PdfSelectedImage {
    private PdfSelectedImage() {}

    public static BufferedImage decode(Path file) throws IOException {
        if (Files.size(file) > 32L * 1024 * 1024)
            throw new IOException("Selected image exceeds32MiB.");
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported bitmap format.");
            var reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Set.of("png", "jpeg", "jpg", "gif", "bmp", "tiff", "tif")
                        .contains(format)) throw new IOException("Unsupported bitmap format.");
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 25_000_000L)
                    throw new IOException("Selected image exceeds the decode pixel limit.");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}

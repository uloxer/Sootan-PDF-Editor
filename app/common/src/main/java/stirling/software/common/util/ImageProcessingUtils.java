package stirling.software.common.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.web.multipart.MultipartFile;

import stirling.software.pdf.PdfAttachmentFiles.SelectedFile;
import stirling.software.pdf.PdfImageInput;

public class ImageProcessingUtils {
    static BufferedImage convertColorType(BufferedImage image, String color) {
        return PdfImageInput.convertColorType(image, color);
    }

    public static byte[] getImageData(BufferedImage image) {
        return PdfImageInput.getImageData(image);
    }

    public static double extractImageOrientation(InputStream input) throws IOException {
        return PdfImageInput.extractImageOrientation(input);
    }

    public static BufferedImage applyOrientation(BufferedImage image, double angle) {
        return PdfImageInput.applyOrientation(image, angle);
    }

    public static BufferedImage loadImageWithExifOrientation(MultipartFile file)
            throws IOException {
        return PdfImageInput.loadImageWithExifOrientation(
                new SelectedFile(
                        file.getOriginalFilename(),
                        file.getContentType(),
                        file.getSize(),
                        file::getInputStream));
    }
}

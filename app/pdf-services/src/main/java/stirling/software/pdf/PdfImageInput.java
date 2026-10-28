package stirling.software.pdf;

import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifSubIFDDirectory;

import lombok.extern.slf4j.Slf4j;

import stirling.software.pdf.PdfAttachmentFiles.SelectedFile;

@Slf4j
public class PdfImageInput {

    public static BufferedImage convertColorType(BufferedImage sourceImage, String colorType) {
        return switch (colorType) {
            case "greyscale" -> {
                BufferedImage convertedImage =
                        new BufferedImage(
                                sourceImage.getWidth(),
                                sourceImage.getHeight(),
                                BufferedImage.TYPE_BYTE_GRAY);
                convertedImage.getGraphics().drawImage(sourceImage, 0, 0, null);
                yield convertedImage;
            }
            case "blackwhite" -> {
                BufferedImage convertedImage =
                        new BufferedImage(
                                sourceImage.getWidth(),
                                sourceImage.getHeight(),
                                BufferedImage.TYPE_BYTE_BINARY);
                convertedImage.getGraphics().drawImage(sourceImage, 0, 0, null);
                yield convertedImage;
            }
            default -> sourceImage;
        };
    }

    public static byte[] getImageData(BufferedImage image) {
        DataBuffer dataBuffer = image.getRaster().getDataBuffer();
        if (dataBuffer instanceof DataBufferByte dataBufferByte) {
            return dataBufferByte.getData();
        } else if (dataBuffer instanceof DataBufferInt dataBufferInt) {
            int[] intData = dataBufferInt.getData();
            ByteBuffer byteBuffer = ByteBuffer.allocate(intData.length * 4);
            byteBuffer.asIntBuffer().put(intData);
            return byteBuffer.array();
        } else {
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = new int[width * height];

            image.getRGB(0, 0, width, height, pixels, 0, width);

            byte[] data = new byte[width * height * 3];
            int index = 0;
            for (int rgb : pixels) {
                data[index++] = (byte) ((rgb >> 16) & 0xFF); // Red
                data[index++] = (byte) ((rgb >> 8) & 0xFF); // Green
                data[index++] = (byte) (rgb & 0xFF); // Blue
            }
            return data;
        }
    }

    public static double extractImageOrientation(InputStream is) throws IOException {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(is);
            ExifSubIFDDirectory directory =
                    metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (directory == null) {
                return 0;
            }
            int orientationTag = directory.getInt(ExifSubIFDDirectory.TAG_ORIENTATION);
            return switch (orientationTag) {
                case 1 -> 0;
                case 6 -> 90;
                case 3 -> 180;
                case 8 -> 270;
                default -> {
                    log.warn("Unknown orientation tag: {}", orientationTag);
                    yield 0;
                }
            };
        } catch (ImageProcessingException | MetadataException e) {
            return 0;
        }
    }

    public static BufferedImage applyOrientation(BufferedImage image, double orientation) {
        if (orientation == 0) {
            return image;
        }
        AffineTransform transform =
                AffineTransform.getRotateInstance(
                        Math.toRadians(orientation),
                        image.getWidth() / 2.0,
                        image.getHeight() / 2.0);
        AffineTransformOp op = new AffineTransformOp(transform, AffineTransformOp.TYPE_BILINEAR);
        return op.filter(image, null);
    }

    public static BufferedImage loadImageWithExifOrientation(SelectedFile file) throws IOException {
        BufferedImage image;
        try (var source = file.open();
                var input = ImageIO.createImageInputStream(source)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported bitmap format.");
            var reader = readers.next();
            try {
                reader.setInput(input);
                PdfImageConversion.validatePixels(reader.getWidth(0), reader.getHeight(0));
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        double orientation;
        try (var source = file.open()) {
            orientation = extractImageOrientation(source);
        }
        return applyOrientation(image, orientation);
    }
}

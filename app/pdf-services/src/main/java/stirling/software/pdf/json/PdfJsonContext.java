package stirling.software.pdf.json;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.SPDF.model.json.PdfJsonFontConversionCandidate;
import stirling.software.SPDF.service.pdfjson.type3.Type3ConversionRequest;

/** Caller-owned document, scratch and optional-helper boundary for the retained JSON processor. */
public interface PdfJsonContext {
    record Configuration(boolean fontNormalization, long cacheMaxBytes, int cacheMaxPercent) {}

    record ProcessResult(int rc) {
        public int getRc() {
            return rc;
        }
    }

    interface Input {
        void transferTo(File target) throws IOException;

        byte[] getBytes() throws IOException;
    }

    interface Resource {
        boolean exists();

        InputStream getInputStream() throws IOException;
    }

    @FunctionalInterface
    interface Resources {
        Resource getResource(String location);
    }

    @FunctionalInterface
    interface Type3Conversions {
        List<PdfJsonFontConversionCandidate> synthesize(Type3ConversionRequest request);
    }

    interface FontPrograms {
        byte[] convertCffProgramToTrueType(byte[] bytes, String unicode);

        byte[] convertCffUsingFontForge(byte[] bytes);

        boolean isCffConversionEnabled();

        String getCffConverterMethod();

        String detectTrueTypeFormat(byte[] bytes);

        String detectFontFlavor(byte[] bytes);

        boolean isCffFontProgram(byte[] bytes);
    }

    final class Temp implements AutoCloseable {
        private final Path path;
        private final Runnable cleanup;

        public Temp(Path path, Runnable cleanup) {
            this.path = path;
            this.cleanup = cleanup;
        }

        public Path getPath() {
            return path;
        }

        public File getFile() {
            return path.toFile();
        }

        public String getAbsolutePath() {
            return getFile().getAbsolutePath();
        }

        public boolean exists() {
            return java.nio.file.Files.exists(path);
        }

        @Override
        public void close() {
            cleanup.run();
        }
    }

    Configuration configuration();

    Temp temp(String suffix) throws IOException;

    PDDocument load(Path path, boolean extraction) throws IOException;

    PDDocument load(byte[] bytes, boolean extraction) throws IOException;

    String jobId();

    boolean addNote(String jobId, String note);

    boolean ghostscriptEnabled();

    ProcessResult ghostscript(List<String> command) throws IOException, InterruptedException;
}

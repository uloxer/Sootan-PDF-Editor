package stirling.software.SPDF.service;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.model.api.PdfJsonConversionProgress;
import stirling.software.SPDF.model.json.PdfJsonDocument;
import stirling.software.SPDF.service.pdfjson.PdfJsonFontService;
import stirling.software.SPDF.service.pdfjson.type3.Type3FontConversionService;
import stirling.software.SPDF.service.pdfjson.type3.Type3GlyphExtractor;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.TaskManager;
import stirling.software.common.util.ProcessExecutor;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.pdf.json.PdfJsonContext;

import tools.jackson.databind.ObjectMapper;

@Service
public class PdfJsonConversionService extends stirling.software.pdf.json.PdfJsonProcessor {
    public PdfJsonConversionService(
            CustomPDFDocumentFactory factory,
            ObjectMapper mapper,
            EndpointConfiguration endpoints,
            TempFileManager temps,
            TaskManager tasks,
            PdfJsonCosMapper cos,
            PdfJsonFallbackFontService fonts,
            PdfJsonFontService programs,
            Type3FontConversionService type3,
            Type3GlyphExtractor glyphs,
            ApplicationProperties properties) {
        super(
                new PdfJsonContext() {
                    public Configuration configuration() {
                        var cfg = properties.getPdfEditor();
                        return cfg == null
                                ? new Configuration(false, -1, 20)
                                : new Configuration(
                                        cfg.getFontNormalization().isEnabled(),
                                        cfg.getCache().getMaxBytes(),
                                        cfg.getCache().getMaxPercent());
                    }

                    public Temp temp(String suffix) throws IOException {
                        var value = new TempFile(temps, suffix);
                        return new Temp(value.getPath(), value::close);
                    }

                    public org.apache.pdfbox.pdmodel.PDDocument load(Path path, boolean extraction)
                            throws IOException {
                        return factory.load(path, extraction);
                    }

                    public org.apache.pdfbox.pdmodel.PDDocument load(
                            byte[] bytes, boolean extraction) throws IOException {
                        return factory.load(bytes, extraction);
                    }

                    public String jobId() {
                        return currentJobId();
                    }

                    public boolean addNote(String id, String note) {
                        return tasks.addNote(id, note);
                    }

                    public boolean ghostscriptEnabled() {
                        return endpoints != null && endpoints.isGroupEnabled("Ghostscript");
                    }

                    public ProcessResult ghostscript(List<String> command)
                            throws IOException, InterruptedException {
                        return new ProcessResult(
                                ProcessExecutor.getInstance(ProcessExecutor.Processes.GHOSTSCRIPT)
                                        .runCommandWithOutputHandling(command)
                                        .getRc());
                    }
                },
                mapper,
                cos,
                fonts,
                programs,
                type3 == null ? null : type3::synthesize,
                glyphs);
    }

    @jakarta.annotation.PostConstruct
    private void initialize() {
        initializeToolAvailability();
    }

    private static String currentJobId() {
        String id = stirling.software.common.util.JobContext.getJobId();
        if (id != null) return id;
        var attrs =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return attrs
                        instanceof
                        org.springframework.web.context.request.ServletRequestAttributes request
                ? (String) request.getRequest().getAttribute("jobId")
                : null;
    }

    private static PdfJsonContext.Input input(MultipartFile file) {
        if (file == null) return null;
        return new PdfJsonContext.Input() {
            public void transferTo(File target) throws IOException {
                file.transferTo(target);
            }

            public byte[] getBytes() throws IOException {
                return file.getBytes();
            }
        };
    }

    public void convertPdfToJson(MultipartFile file, OutputStream out) throws IOException {
        super.exportJson(input(file), out);
    }

    public PdfJsonDocument convertPdfToJsonDocument(MultipartFile file) throws IOException {
        return super.exportJsonDocument(input(file));
    }

    public void convertPdfToJson(MultipartFile file, boolean lightweight, OutputStream out)
            throws IOException {
        super.exportJson(input(file), lightweight, out);
    }

    public void convertPdfToJson(
            MultipartFile file, Consumer<PdfJsonConversionProgress> progress, OutputStream out)
            throws IOException {
        super.exportJson(input(file), progress, out);
    }

    public void convertPdfToJson(
            MultipartFile file,
            Consumer<PdfJsonConversionProgress> progress,
            boolean lightweight,
            OutputStream out)
            throws IOException {
        super.exportJson(input(file), progress, lightweight, out);
    }

    public void convertJsonToPdf(MultipartFile file, OutputStream out) throws IOException {
        super.importJson(input(file), out);
    }

    public void extractDocumentMetadata(MultipartFile file, String jobId, OutputStream out)
            throws IOException {
        super.extractMetadata(input(file), jobId, out);
    }
}

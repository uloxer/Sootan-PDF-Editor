package stirling.software.SPDF.service;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import stirling.software.pdf.PdfAttachments;

@Service
public class AttachmentService extends PdfAttachments implements AttachmentServiceInterface {
    public AttachmentService() {
        super();
    }

    public AttachmentService(long maxFile, long maxTotal) {
        super(maxFile, maxTotal);
    }

    @Override
    public PDDocument addAttachment(PDDocument document, List<MultipartFile> files)
            throws IOException {
        return addFiles(document, AttachmentFiles.selected(files));
    }
}

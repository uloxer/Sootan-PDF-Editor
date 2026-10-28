package stirling.software.SPDF.service;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import stirling.software.pdf.PdfPortfolio;

@Service
public class PortfolioService extends PdfPortfolio implements PortfolioServiceInterface {
    @Override
    public PDDocument createPortfolio(List<MultipartFile> files, String title) throws IOException {
        return createFiles(AttachmentFiles.selected(files), title);
    }
}

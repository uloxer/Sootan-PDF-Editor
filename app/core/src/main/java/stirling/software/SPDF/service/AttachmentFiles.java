package stirling.software.SPDF.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import stirling.software.pdf.PdfAttachmentFiles.SelectedFile;

final class AttachmentFiles {
    private AttachmentFiles() {}

    static List<SelectedFile> selected(List<MultipartFile> files) {
        if (files == null) return List.of();
        return files.stream()
                .map(
                        file ->
                                new SelectedFile(
                                        file.getOriginalFilename(),
                                        file.getContentType(),
                                        file.getSize(),
                                        file::getInputStream))
                .toList();
    }
}

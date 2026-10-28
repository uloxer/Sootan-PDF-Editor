package stirling.software.pdf;

import static stirling.software.pdf.PdfAttachmentFiles.setCatalogViewerPreferences;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PageMode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.common.filespecification.PDFileSpecification;

import io.github.pixee.security.Filenames;

import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.model.api.misc.AttachmentInfo;

@Slf4j
public class PdfAttachments {

    private static final long DEFAULT_MAX_ATTACHMENT_SIZE_BYTES = 50L * 1024 * 1024; // 50 MB
    private static final long DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE_BYTES =
            200L * 1024 * 1024; // 200 MB

    private final boolean strictLimits;
    private final long maxAttachmentSizeBytes;
    private final long maxTotalAttachmentSizeBytes;

    public PdfAttachments() {
        this(DEFAULT_MAX_ATTACHMENT_SIZE_BYTES, DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE_BYTES);
    }

    public PdfAttachments(long maxAttachmentSizeBytes, long maxTotalAttachmentSizeBytes) {
        this(maxAttachmentSizeBytes, maxTotalAttachmentSizeBytes, false);
    }

    public PdfAttachments(
            long maxAttachmentSizeBytes, long maxTotalAttachmentSizeBytes, boolean strictLimits) {
        this.strictLimits = strictLimits;
        this.maxAttachmentSizeBytes = maxAttachmentSizeBytes;
        this.maxTotalAttachmentSizeBytes = maxTotalAttachmentSizeBytes;
    }

    public PDDocument addFiles(
            PDDocument document, List<PdfAttachmentFiles.SelectedFile> attachments)
            throws IOException {
        PDEmbeddedFilesNameTreeNode embeddedFilesTree = getEmbeddedFilesTree(document);
        Map<String, PDComplexFileSpecification> existingNames;

        try {
            Map<String, PDComplexFileSpecification> names = new LinkedHashMap<>();
            collectEmbeddedFiles(embeddedFilesTree, names);

            if (names == null) {
                log.debug("No existing embedded files found, creating new names map.");
                existingNames = new HashMap<>();
            } else {
                existingNames = new LinkedHashMap<>();
                collectEmbeddedFiles(embeddedFilesTree, existingNames);
                log.debug("Embedded files: {}", existingNames.keySet());
            }
        } catch (IOException e) {
            log.error("Could not retrieve existing embedded files", e);
            throw e;
        }

        for (PdfAttachmentFiles.SelectedFile attachment : attachments) {
            String filename = attachment.name();

            // A batch that cannot read one staged file must fail, not save the
            // rest: callers return this document as the operation result.
            PDEmbeddedFile embeddedFile;
            try (var stream = attachment.open()) {
                embeddedFile = new PDEmbeddedFile(document, stream);
            }
            embeddedFile.setSize((int) attachment.size());
            // use java.time.Instant and convert to GregorianCalendar for PDFBox
            Instant now = Instant.now();
            GregorianCalendar nowCal =
                    GregorianCalendar.from(ZonedDateTime.ofInstant(now, ZoneId.systemDefault()));
            embeddedFile.setCreationDate(nowCal);
            embeddedFile.setModDate(nowCal);
            String contentType = attachment.contentType();
            if (StringUtils.isNotBlank(contentType)) {
                embeddedFile.setSubtype(contentType);
            }

            // Create attachments specification and associate embedded attachment with
            // file
            PDComplexFileSpecification fileSpecification = new PDComplexFileSpecification();
            fileSpecification.setFile(filename);
            fileSpecification.setFileUnicode(filename);
            fileSpecification.setFileDescription("Embedded attachment: " + filename);
            fileSpecification.setEmbeddedFile(embeddedFile);
            fileSpecification.setEmbeddedFileUnicode(embeddedFile);

            if (existingNames.containsKey(filename))
                throw new IOException("Attachment name already exists.");
            existingNames.put(filename, fileSpecification);

            log.info("Added attachment: {} ({} bytes)", filename, attachment.size());
        }

        embeddedFilesTree.setKids(null);
        embeddedFilesTree.setNames(existingNames);
        setCatalogViewerPreferences(document, PageMode.USE_ATTACHMENTS);

        return document;
    }

    public Optional<byte[]> extractAttachments(PDDocument document) throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        if (catalog == null) {
            return Optional.empty();
        }

        PDDocumentNameDictionary documentNames = catalog.getNames();
        if (documentNames == null) {
            return Optional.empty();
        }

        PDEmbeddedFilesNameTreeNode embeddedFilesTree = documentNames.getEmbeddedFiles();
        if (embeddedFilesTree == null) {
            return Optional.empty();
        }

        Map<String, PDComplexFileSpecification> embeddedFiles = new LinkedHashMap<>();
        collectEmbeddedFiles(embeddedFilesTree, embeddedFiles);

        if (embeddedFiles.isEmpty()) {
            return Optional.empty();
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ZipOutputStream zipOutputStream = new ZipOutputStream(baos)) {
            Set<String> usedNames = new HashSet<>();
            boolean hasExtractedAttachments = false;
            long totalBytesWritten = 0L;

            for (Map.Entry<String, PDComplexFileSpecification> entry : embeddedFiles.entrySet()) {
                PDComplexFileSpecification fileSpecification = entry.getValue();
                PDEmbeddedFile embeddedFile = getEmbeddedFile(fileSpecification);

                if (embeddedFile == null) {
                    log.debug(
                            "Skipping attachment {} because embedded file was null",
                            entry.getKey());
                    continue;
                }

                String filename = determineFilename(entry.getKey(), fileSpecification);
                filename = Filenames.toSimpleFileName(filename);
                String sanitizedFilename = sanitizeFilename(filename);

                Optional<byte[]> attachmentData = readAttachmentData(embeddedFile);
                if (attachmentData.isEmpty()) {
                    if (strictLimits) throw new IOException("Attachment exceeds extraction limit.");
                    log.warn(
                            "Skipping attachment '{}' because it exceeds the size limit of {} bytes",
                            sanitizedFilename,
                            maxAttachmentSizeBytes);
                    continue;
                }

                byte[] data = attachmentData.get();
                if (maxTotalAttachmentSizeBytes > 0
                        && (data.length + totalBytesWritten) > maxTotalAttachmentSizeBytes) {
                    if (strictLimits) throw new IOException("Attachments exceed extraction total.");
                    log.warn(
                            "Skipping attachment '{}' because the total size would exceed {} bytes",
                            sanitizedFilename,
                            maxTotalAttachmentSizeBytes);
                    continue;
                }

                String uniqueFilename = ensureUniqueFilename(sanitizedFilename, usedNames);

                ZipEntry zipEntry = new ZipEntry(uniqueFilename);
                if (embeddedFile.getModDate() != null) {
                    zipEntry.setLastModifiedTime(
                            FileTime.from(embeddedFile.getModDate().toInstant()));
                }
                if (embeddedFile.getCreationDate() != null) {
                    zipEntry.setCreationTime(
                            FileTime.from(embeddedFile.getCreationDate().toInstant()));
                }
                zipEntry.setSize(data.length);

                zipOutputStream.putNextEntry(zipEntry);
                zipOutputStream.write(data);
                zipOutputStream.closeEntry();
                hasExtractedAttachments = true;
                totalBytesWritten += data.length;
                log.info("Extracted attachment '{}' ({} bytes)", uniqueFilename, data.length);
            }

            zipOutputStream.finish();

            if (!hasExtractedAttachments) {
                return Optional.empty();
            }

            return Optional.of(baos.toByteArray());
        }
    }

    public Optional<byte[]> extractSingleAttachment(PDDocument document, String attachmentName)
            throws IOException {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        if (catalog == null) {
            return Optional.empty();
        }

        PDDocumentNameDictionary documentNames = catalog.getNames();
        if (documentNames == null) {
            return Optional.empty();
        }

        PDEmbeddedFilesNameTreeNode embeddedFilesTree = documentNames.getEmbeddedFiles();
        if (embeddedFilesTree == null) {
            return Optional.empty();
        }

        Map<String, PDComplexFileSpecification> embeddedFiles = new LinkedHashMap<>();
        collectEmbeddedFiles(embeddedFilesTree, embeddedFiles);

        Optional<Map.Entry<String, PDComplexFileSpecification>> match =
                findAttachmentEntry(embeddedFiles, attachmentName);
        if (match.isPresent()) {
            PDEmbeddedFile embeddedFile = getEmbeddedFile(match.get().getValue());
            if (embeddedFile != null) {
                return readAttachmentData(embeddedFile);
            }
        }
        return Optional.empty();
    }

    public List<AttachmentInfo> listAttachments(PDDocument document) throws IOException {
        List<AttachmentInfo> attachments = new ArrayList<>();

        PDDocumentCatalog catalog = document.getDocumentCatalog();
        if (catalog == null) {
            return attachments;
        }

        PDDocumentNameDictionary documentNames = catalog.getNames();
        if (documentNames == null) {
            return attachments;
        }

        PDEmbeddedFilesNameTreeNode embeddedFilesTree = documentNames.getEmbeddedFiles();
        if (embeddedFilesTree == null) {
            return attachments;
        }

        Map<String, PDComplexFileSpecification> embeddedFiles = new LinkedHashMap<>();
        collectEmbeddedFiles(embeddedFilesTree, embeddedFiles);

        for (Map.Entry<String, PDComplexFileSpecification> entry : embeddedFiles.entrySet()) {
            PDComplexFileSpecification fileSpecification = entry.getValue();
            PDEmbeddedFile embeddedFile = getEmbeddedFile(fileSpecification);

            if (embeddedFile != null) {
                String filename = determineFilename(entry.getKey(), fileSpecification);
                String description = fileSpecification.getFileDescription();
                String contentType = embeddedFile.getSubtype();
                Long size = (long) embeddedFile.getSize();

                String creationDate = null;
                if (embeddedFile.getCreationDate() != null) {
                    creationDate = embeddedFile.getCreationDate().getTime().toString();
                }

                String modificationDate = null;
                if (embeddedFile.getModDate() != null) {
                    modificationDate = embeddedFile.getModDate().getTime().toString();
                }

                AttachmentInfo attachmentInfo =
                        new AttachmentInfo(
                                filename,
                                size,
                                contentType,
                                description,
                                creationDate,
                                modificationDate);

                attachments.add(attachmentInfo);
            }
        }

        return attachments;
    }

    public PDDocument renameAttachment(PDDocument document, String attachmentName, String newName)
            throws IOException {
        PDEmbeddedFilesNameTreeNode embeddedFilesTree = getEmbeddedFilesTree(document);

        Map<String, PDComplexFileSpecification> allEmbeddedFiles = new LinkedHashMap<>();
        collectEmbeddedFiles(embeddedFilesTree, allEmbeddedFiles);

        Optional<Map.Entry<String, PDComplexFileSpecification>> match =
                findAttachmentEntry(allEmbeddedFiles, attachmentName);
        PDComplexFileSpecification fileToRename = null;
        String keyToRename = null;
        if (match.isPresent()) {
            fileToRename = match.get().getValue();
            keyToRename = match.get().getKey();
        }

        if (fileToRename == null || keyToRename == null) {
            log.warn("Attachment '{}' not found for renaming", attachmentName);
            throw PdfAttachmentFiles.argument(
                    "error.attachmentNotFound",
                    "Attachment ''{0}'' not found for renaming",
                    attachmentName);
        }

        if (!newName.equals(keyToRename) && allEmbeddedFiles.containsKey(newName))
            throw new IOException("Attachment name already exists.");
        var collection =
                document.getDocumentCatalog()
                        .getCOSObject()
                        .getCOSDictionary(org.apache.pdfbox.cos.COSName.getPDFName("Collection"));
        if (collection != null && keyToRename.equals(collection.getString("D")))
            collection.setString("D", newName);
        fileToRename.setFile(newName);
        fileToRename.setFileUnicode(newName);

        allEmbeddedFiles.remove(keyToRename);
        allEmbeddedFiles.put(newName, fileToRename);

        embeddedFilesTree.setKids(null);

        embeddedFilesTree.setNames(allEmbeddedFiles);
        log.info("Renamed attachment from '{}' to '{}'", attachmentName, newName);

        return document;
    }

    public PDDocument deleteAttachment(PDDocument document, String attachmentName)
            throws IOException {
        PDEmbeddedFilesNameTreeNode embeddedFilesTree = getEmbeddedFilesTree(document);

        Map<String, PDComplexFileSpecification> allEmbeddedFiles = new LinkedHashMap<>();
        collectEmbeddedFiles(embeddedFilesTree, allEmbeddedFiles);

        Optional<Map.Entry<String, PDComplexFileSpecification>> match =
                findAttachmentEntry(allEmbeddedFiles, attachmentName);
        String keyToRemove = match.map(Map.Entry::getKey).orElse(null);

        if (keyToRemove == null) {
            log.warn("Attachment '{}' not found for deletion", attachmentName);
            throw PdfAttachmentFiles.argument(
                    "error.attachmentNotFound",
                    "Attachment ''{0}'' not found for deletion",
                    attachmentName);
        }

        var removed = allEmbeddedFiles.remove(keyToRemove);
        var catalog = document.getDocumentCatalog().getCOSObject();
        var associated = catalog.getCOSArray(org.apache.pdfbox.cos.COSName.getPDFName("AF"));
        if (associated != null)
            for (int i = associated.size() - 1; i >= 0; i--) {
                if (associated.getObject(i) == removed.getCOSObject()) associated.remove(i);
            }
        var collection =
                catalog.getCOSDictionary(org.apache.pdfbox.cos.COSName.getPDFName("Collection"));
        if (collection != null && keyToRemove.equals(collection.getString("D")))
            collection.removeItem(org.apache.pdfbox.cos.COSName.getPDFName("D"));

        embeddedFilesTree.setKids(null);

        embeddedFilesTree.setNames(allEmbeddedFiles);
        log.info("Deleted attachment: '{}'", attachmentName);

        return document;
    }

    /**
     * Entry whose name matches the target. A case-sensitive match wins outright; otherwise a
     * case-insensitive full-name match is only used when it is unique, so Report.pdf/REPORT.PDF
     * cannot resolve by map order. Falls back to simplified and decoded matching only when no full
     * name matches, and only when that fallback is unambiguous.
     */
    private Optional<Map.Entry<String, PDComplexFileSpecification>> findAttachmentEntry(
            Map<String, PDComplexFileSpecification> embeddedFiles, String targetName) {
        if (StringUtils.isBlank(targetName)) {
            return Optional.empty();
        }
        String normTarget = targetName.trim();

        Map.Entry<String, PDComplexFileSpecification> caseInsensitive = null;
        for (Map.Entry<String, PDComplexFileSpecification> entry : embeddedFiles.entrySet()) {
            String candidateName = determineFilename(entry.getKey(), entry.getValue());
            if (normTarget.equals(candidateName) || normTarget.equals(entry.getKey())) {
                return Optional.of(entry);
            }
            if (normTarget.equalsIgnoreCase(candidateName)
                    || normTarget.equalsIgnoreCase(entry.getKey())) {
                if (caseInsensitive != null) {
                    return Optional.empty();
                }
                caseInsensitive = entry;
            }
        }
        if (caseInsensitive != null) {
            return Optional.of(caseInsensitive);
        }

        Map.Entry<String, PDComplexFileSpecification> fallback = null;
        for (Map.Entry<String, PDComplexFileSpecification> entry : embeddedFiles.entrySet()) {
            if (matchesAttachmentName(
                    determineFilename(entry.getKey(), entry.getValue()),
                    entry.getKey(),
                    targetName)) {
                if (fallback != null) {
                    return Optional.empty();
                }
                fallback = entry;
            }
        }
        return Optional.ofNullable(fallback);
    }

    private boolean matchesAttachmentName(
            String candidateName, String entryKey, String targetName) {
        if (StringUtils.isBlank(targetName)) {
            return false;
        }
        String normTarget = targetName.trim();
        if (normTarget.equalsIgnoreCase(candidateName) || normTarget.equalsIgnoreCase(entryKey)) {
            return true;
        }
        String simpleCandidate = Filenames.toSimpleFileName(candidateName);
        String simpleKey = Filenames.toSimpleFileName(entryKey);
        String simpleTarget = Filenames.toSimpleFileName(normTarget);
        if (simpleTarget.equalsIgnoreCase(simpleCandidate)
                || simpleTarget.equalsIgnoreCase(simpleKey)) {
            return true;
        }
        // URL-decoded comparison so a client that sends %20 etc. still matches; malformed
        // percent-encodings are just not a match rather than a hard failure.
        try {
            String decodedTarget = decodeAttachmentName(normTarget);
            String decodedCandidate = decodeAttachmentName(candidateName);
            String decodedKey = decodeAttachmentName(entryKey);
            if (decodedTarget.equalsIgnoreCase(decodedCandidate)
                    || decodedTarget.equalsIgnoreCase(decodedKey)) {
                return true;
            }
        } catch (IllegalArgumentException malformedEncoding) {
            // not a match
        }
        return false;
    }

    /**
     * Percent-decodes a client-supplied name while keeping literal '+' characters. URLDecoder alone
     * form-decodes '+' to a space, which would make a request for "a b.txt" match an attachment
     * named "a+b.txt".
     */
    private static String decodeAttachmentName(String name) {
        return java.net.URLDecoder.decode(
                name.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String sanitizeFilename(String candidate) {
        String sanitized = Filenames.toSimpleFileName(candidate);
        sanitized = sanitized.replaceAll("[\\x00-\\x1f]", "_");
        if (StringUtils.isBlank(sanitized) || sanitized.equals(".") || sanitized.equals("..")) {
            sanitized = generateDefaultFilename();
        }
        return sanitized;
    }

    private String generateDefaultFilename() {
        return "unknown_attachment";
    }

    private Optional<byte[]> readAttachmentData(PDEmbeddedFile embeddedFile) throws IOException {
        try (var inputStream = embeddedFile.createInputStream();
                var buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            long total = 0L;
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                total += read;
                if (maxAttachmentSizeBytes > 0 && total > maxAttachmentSizeBytes) {
                    return Optional.empty();
                }
                buffer.write(chunk, 0, read);
            }
            return Optional.of(buffer.toByteArray());
        }
    }

    private void collectEmbeddedFiles(
            PDNameTreeNode<PDComplexFileSpecification> node,
            Map<String, PDComplexFileSpecification> collector)
            throws IOException {
        var pending = new java.util.ArrayDeque<PDNameTreeNode<PDComplexFileSpecification>>();
        var seen =
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<
                                org.apache.pdfbox.cos.COSDictionary, Boolean>());
        if (node != null) pending.add(node);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!seen.add(current.getCOSObject()))
                throw new IOException("Cyclic embedded file tree.");
            if (seen.size() > 10000) throw new IOException("Embedded file tree exceeds limit.");
            var names = current.getNames();
            if (names != null) collector.putAll(names);
            var kids = current.getKids();
            if (kids != null) pending.addAll(kids);
            if (collector.size() > 10000) throw new IOException("Too many embedded files.");
        }
    }

    private PDEmbeddedFile getEmbeddedFile(PDFileSpecification fileSpecification) {
        if (!(fileSpecification instanceof PDComplexFileSpecification complexSpecification)) {
            return null;
        }

        if (complexSpecification.getEmbeddedFileUnicode() != null) {
            return complexSpecification.getEmbeddedFileUnicode();
        }
        if (complexSpecification.getEmbeddedFile() != null) {
            return complexSpecification.getEmbeddedFile();
        }
        if (complexSpecification.getEmbeddedFileDos() != null) {
            return complexSpecification.getEmbeddedFileDos();
        }
        if (complexSpecification.getEmbeddedFileMac() != null) {
            return complexSpecification.getEmbeddedFileMac();
        }
        return complexSpecification.getEmbeddedFileUnix();
    }

    private String determineFilename(String key, PDComplexFileSpecification specification) {
        if (specification == null) {
            return fallbackFilename(key);
        }

        String name = specification.getFileUnicode();
        if (StringUtils.isBlank(name)) {
            name = specification.getFilename();
        }
        if (StringUtils.isBlank(name)) {
            name = specification.getFile();
        }
        if (StringUtils.isBlank(name)) {
            name = key;
        }
        return fallbackFilename(name);
    }

    private String fallbackFilename(String candidate) {
        if (StringUtils.isBlank(candidate)) {
            return "unknown_attachment";
        }
        return candidate;
    }

    private String ensureUniqueFilename(String filename, Set<String> usedNames) {
        String baseName = filename;
        String extension = "";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0 && lastDot < filename.length() - 1) {
            baseName = filename.substring(0, lastDot);
            extension = filename.substring(lastDot);
        }

        String uniqueName = filename;
        int counter = 1;
        while (usedNames.contains(uniqueName.toLowerCase(java.util.Locale.ROOT))) {
            uniqueName = baseName + "_" + counter + extension;
            counter++;
        }

        usedNames.add(uniqueName.toLowerCase(java.util.Locale.ROOT));
        return uniqueName;
    }

    private PDEmbeddedFilesNameTreeNode getEmbeddedFilesTree(PDDocument document) {
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDDocumentNameDictionary documentNames = catalog.getNames();

        if (documentNames == null) {
            documentNames = new PDDocumentNameDictionary(catalog);
        }

        catalog.setNames(documentNames);
        PDEmbeddedFilesNameTreeNode embeddedFilesTree = documentNames.getEmbeddedFiles();

        if (embeddedFilesTree == null) {
            embeddedFilesTree = new PDEmbeddedFilesNameTreeNode();
            documentNames.setEmbeddedFiles(embeddedFilesTree);
        }
        return embeddedFilesTree;
    }
}

package stirling.software.pdf;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** Protection changes require owner authentication; signed inputs are never rewritten. */
public final class PdfProtection {
    private PdfProtection() {}

    public static void requireOwner(PDDocument document) throws IOException {
        if (!document.getSignatureDictionaries().isEmpty())
            throw new IOException("Changing signed PDFs is not supported.");
        if (document.isEncrypted() && document.getCurrentAccessPermission().isReadOnly())
            throw new IOException("This operation requires the permissions password.");
    }

    public static void encrypt(
            PDDocument document, String owner, String reader, AccessPermission permissions)
            throws IOException {
        requireOwner(document);
        if (owner.isEmpty() && reader.isEmpty()) throw new IOException("Enter a password.");
        if (!permissions.isOwnerPermission() && (owner.isEmpty() || owner.equals(reader)))
            throw new IOException("Restrictions require a separate permissions password.");
        var policy = new StandardProtectionPolicy(owner, reader, permissions);
        policy.setEncryptionKeyLength(256);
        policy.setPreferAES(true);
        document.protect(policy);
    }

    public static void decrypt(PDDocument document) throws IOException {
        requireOwner(document);
        document.setAllSecurityToBeRemoved(true);
    }
}

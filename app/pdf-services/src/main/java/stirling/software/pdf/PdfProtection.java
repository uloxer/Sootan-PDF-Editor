package stirling.software.pdf;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

/** Protection changes require owner authentication; signed inputs are never rewritten. */
public final class PdfProtection {
    private PdfProtection() {}

    /**
     * PDFBox distinguishes owner authentication by mutable permissions, not permissive flag bits.
     */
    public static boolean isOwnerAuthenticated(PDDocument document) throws IOException {
        if (!document.isEncrypted()) return false;
        org.apache.pdfbox.pdmodel.encryption.SecurityHandler<?> handler =
                document.getEncryption().getSecurityHandler();
        if (!(handler instanceof org.apache.pdfbox.pdmodel.encryption.StandardSecurityHandler))
            throw new IOException("Only password-based protection is supported.");
        return !document.getCurrentAccessPermission().isReadOnly();
    }

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

    /**
     * Reuses the controller's credential-preserving branch; the caller saves and rechecks encoded
     * password roles.
     */
    public static void add(
            PDDocument document,
            String owner,
            String reader,
            String source,
            int keyLength,
            boolean preserve,
            AccessPermission permissions)
            throws IOException {
        requireOwner(document);
        if (preserve && document.isEncrypted()) {
            if (owner.isEmpty()) {
                if (!permissions.isOwnerPermission()
                        && !PdfPasswordRoles.hasSeparateOwnerPassword(document, source))
                    throw new IOException(
                            "Choose a separate permissions password to restrict actions.");
                PdfPermissionSecurityHandler.changePermissions(document, source, permissions);
                return;
            }
            if (!PdfPasswordRoles.isOpeningPassword(document, reader)) {
                if (!PdfPasswordRoles.isOpeningPassword(document, source))
                    throw new IOException(
                            "The opening password is required to replace the permissions password.");
                reader = source;
            }
            keyLength = document.getEncryption().getLength();
        }
        if (!permissions.isOwnerPermission() && (owner.isEmpty() || owner.equals(reader)))
            throw new IOException("Restrictions require a separate permissions password.");
        if (owner.isEmpty() && reader.isEmpty()) throw new IOException("Enter a password.");
        if (keyLength != 40 && keyLength != 128 && keyLength != 256)
            throw new IOException("Unsupported encryption key length.");
        var protection = new StandardProtectionPolicy(owner, reader, permissions);
        protection.setEncryptionKeyLength(keyLength);
        protection.setPreferAES(keyLength >= 128);
        document.protect(protection);
    }

    /**
     * Selection credentials never implicitly select removal; owner authentication is mandatory
     * offline.
     */
    public static void remove(
            PDDocument document,
            String opening,
            String owner,
            String retained,
            String credential,
            boolean selective,
            boolean removeAll)
            throws IOException {
        remove(document, opening, owner, retained, credential, selective, removeAll, true);
    }

    /** Server policy may relax owner enforcement; signatures are always protected. */
    public static void remove(
            PDDocument document,
            String opening,
            String owner,
            String retained,
            String credential,
            boolean selective,
            boolean removeAll,
            boolean honorOwner)
            throws IOException {
        if (honorOwner) requireOwner(document);
        else if (!document.getSignatureDictionaries().isEmpty())
            throw new IOException("Changing signed PDFs is not supported.");
        if (removeAll) {
            document.setAllSecurityToBeRemoved(true);
            return;
        }
        if (!selective) {
            document.setAllSecurityToBeRemoved(true);
            if (document.isEncrypted()) {
                var permissions = new AccessPermission(document.getEncryption().getPermissions());
                if (!permissions.isOwnerPermission()) {
                    var random = new byte[32];
                    new java.security.SecureRandom().nextBytes(random);
                    var protection =
                            new StandardProtectionPolicy(
                                    java.util.HexFormat.of().formatHex(random), "", permissions);
                    protection.setEncryptionKeyLength(256);
                    document.setAllSecurityToBeRemoved(false);
                    document.protect(protection);
                }
            }
            return;
        }
        boolean removeOpening = !opening.isEmpty(), removeOwner = !owner.isEmpty();
        if (!removeOpening && !removeOwner) throw new OptionException("Enter a password to remove");
        boolean openingSet =
                document.isEncrypted() && !PdfPasswordRoles.isOpeningPassword(document, "");
        boolean ownerSet = PdfPasswordRoles.hasSeparateOwnerPassword(document, credential);
        if (removeOpening
                && (!openingSet || !PdfPasswordRoles.isOpeningPassword(document, opening)))
            throw new OptionException("The opening password is incorrect or is not set");
        if (removeOwner && (!ownerSet || !isOwnerAuthenticated(document)))
            throw new OptionException("The permissions password is incorrect or is not set");
        if ((!openingSet || removeOpening) && (!ownerSet || removeOwner))
            document.setAllSecurityToBeRemoved(true);
        else {
            if (removeOpening && !isOwnerAuthenticated(document))
                throw new OptionException("Verify the permissions password to retain it");
            if (!removeOpening && !PdfPasswordRoles.isOpeningPassword(document, retained))
                throw new OptionException("The retained opening password is required");
            var protection =
                    new StandardProtectionPolicy(
                            removeOpening ? credential : "",
                            removeOpening ? "" : retained,
                            removeOpening
                                    ? new AccessPermission(
                                            document.getEncryption().getPermissions())
                                    : new AccessPermission());
            protection.setEncryptionKeyLength(document.getEncryption().getLength());
            protection.setPreferAES(document.getEncryption().getVersion() >= 4);
            document.protect(protection);
        }
    }

    /** Invalid role selections map to the HTTP controller's existing bad-request contract. */
    public static final class OptionException extends IOException {
        public OptionException(String message) {
            super(message);
        }
    }
}

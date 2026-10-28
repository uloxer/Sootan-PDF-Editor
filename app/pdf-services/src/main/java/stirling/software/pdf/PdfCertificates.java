package stirling.software.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Local PKCS12 signing and cryptographic integrity; certificate trust is reported separately. */
public final class PdfCertificates {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private PdfCertificates() {}

    public static void sign(
            Path input, Path output, Path certificate, char[] password, String reason)
            throws Exception {
        sign(input, output, certificate, password, reason, "", "", false, 1, true);
    }

    /** Signs locally; visible page numbers are one-based and all appearance bytes are bundled. */
    public static void sign(
            Path input,
            Path output,
            Path certificate,
            char[] password,
            String reason,
            String location,
            String name,
            boolean visible,
            int pageNumber,
            boolean logo)
            throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(certificate)) {
            store.load(stream, password);
        }
        String alias = null;
        for (var aliases = store.aliases(); aliases.hasMoreElements(); ) {
            String candidate = aliases.nextElement();
            if (store.isKeyEntry(candidate)) {
                alias = candidate;
                break;
            }
        }
        if (alias == null) throw new IOException("The selected certificate has no signing key.");
        var key = (PrivateKey) store.getKey(alias, password);
        var chain = store.getCertificateChain(alias);
        ((X509Certificate) chain[0]).checkValidity();
        boolean[] signingUsage = ((X509Certificate) chain[0]).getKeyUsage();
        if (signingUsage != null
                && !signingUsage[0]
                && (signingUsage.length < 2 || !signingUsage[1]))
            throw new IOException("The certificate key usage does not allow signing.");
        try (var document = Loader.loadPDF(input.toFile());
                var stream = Files.newOutputStream(output)) {
            if (document.isEncrypted())
                throw new IOException("Protected signing is not connected.");
            var signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(((X509Certificate) chain[0]).getSubjectX500Principal().getName());
            signature.setReason(reason);
            signature.setLocation(location);
            if (!name.isEmpty()) signature.setName(name);
            signature.setSignDate(Calendar.getInstance());
            org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface signer =
                    content -> {
                        try {
                            return DetachedCmsSigner.sign(content, key, chain, null).getEncoded();
                        } catch (Exception ex) {
                            throw new IOException("Certificate signing failed.", ex);
                        }
                    };
            try (var settings =
                    new org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions()) {
                if (visible) {
                    if (pageNumber < 1 || pageNumber > document.getNumberOfPages())
                        throw new IOException("Signature page is outside the document.");
                    var page = document.getPage(pageNumber - 1);
                    if (page.getCropBox().getWidth() < 200 || page.getCropBox().getHeight() < 50)
                        throw new IOException("The signature appearance does not fit this page.");
                    settings.setPage(pageNumber - 1);
                    settings.setVisualSignature(
                            PdfVisibleSignature.create(
                                    document,
                                    signature,
                                    pageNumber - 1,
                                    logo,
                                    (X509Certificate) chain[0]));
                    document.addSignature(signature, signer, settings);
                } else document.addSignature(signature, signer);
                document.saveIncremental(stream);
            }
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    public static List<Map<String, Object>> integrity(Path input) throws Exception {
        return integrity(input, null);
    }

    /**
     * Reports CMS integrity separately from offline local-root trust and current certificate
     * validity.
     */
    public static List<Map<String, Object>> integrity(Path input, Path selectedTrust)
            throws Exception {
        X509Certificate custom = null;
        if (selectedTrust != null) {
            if (Files.size(selectedTrust) > 1024 * 1024)
                throw new IOException("Trust certificate exceeds one MiB.");
            try (var stream = Files.newInputStream(selectedTrust)) {
                custom =
                        (X509Certificate)
                                java.security.cert.CertificateFactory.getInstance("X.509")
                                        .generateCertificate(stream);
            }
        }
        byte[] bytes = Files.readAllBytes(input);
        var results = new ArrayList<Map<String, Object>>();
        try (var document = Loader.loadPDF(bytes)) {
            for (var signature : document.getSignatureDictionaries()) {
                var result = new LinkedHashMap<String, Object>();
                var cms =
                        new CMSSignedData(
                                new CMSProcessableByteArray(signature.getSignedContent(bytes)),
                                new java.io.ByteArrayInputStream(signature.getContents(bytes)));
                if (cms.getSignerInfos().getSigners().size() != 1)
                    throw new IOException(
                            "Multiple CMS signers in one signature are not connected.");
                boolean valid = true;
                X509Certificate signerCertificate = null;
                var embedded = new ArrayList<X509Certificate>();
                var converter = new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter();
                for (var holder : cms.getCertificates().getMatches(null))
                    embedded.add(converter.getCertificate(holder));
                for (var signer : cms.getSignerInfos().getSigners()) {
                    var matches = cms.getCertificates().getMatches(signer.getSID());
                    if (matches.isEmpty()) {
                        valid = false;
                        continue;
                    }
                    var holder = (X509CertificateHolder) matches.iterator().next();
                    signerCertificate = converter.getCertificate(holder);
                    try {
                        valid &=
                                signer.verify(
                                        new JcaSimpleSignerInfoVerifierBuilder().build(holder));
                    } catch (Exception invalid) {
                        valid = false;
                    }
                }
                int[] range = signature.getByteRange();
                result.put("valid", valid && !cms.getSignerInfos().getSigners().isEmpty());
                result.put(
                        "coversEntireDocument",
                        range.length == 4 && (long) range[2] + range[3] == bytes.length);
                boolean trusted = false;
                boolean notExpired = false;
                result.put("validationTimeSource", "current");
                result.put(
                        "signatureDate",
                        signature.getSignDate() == null
                                ? ""
                                : signature.getSignDate().toInstant().toString());
                result.put("reason", signature.getReason() == null ? "" : signature.getReason());
                result.put(
                        "location", signature.getLocation() == null ? "" : signature.getLocation());
                if (signerCertificate != null) {
                    var cert = signerCertificate;
                    result.put("subjectDN", cert.getSubjectX500Principal().getName());
                    result.put("issuerDN", cert.getIssuerX500Principal().getName());
                    result.put("serialNumber", cert.getSerialNumber().toString(16));
                    result.put("validFrom", cert.getNotBefore().toInstant().toString());
                    result.put("validUntil", cert.getNotAfter().toInstant().toString());
                    result.put("signatureAlgorithm", cert.getSigAlgName());
                    result.put("version", cert.getVersion());
                    result.put(
                            "keySize",
                            cert.getPublicKey() instanceof java.security.interfaces.RSAKey rsa
                                    ? rsa.getModulus().bitLength()
                                    : cert.getPublicKey()
                                                    instanceof java.security.interfaces.ECKey ec
                                            ? ec.getParams().getOrder().bitLength()
                                            : null);
                    result.put(
                            "selfSigned",
                            cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal()));
                    var names =
                            List.of(
                                    "digitalSignature",
                                    "nonRepudiation",
                                    "keyEncipherment",
                                    "dataEncipherment",
                                    "keyAgreement",
                                    "keyCertSign",
                                    "cRLSign",
                                    "encipherOnly",
                                    "decipherOnly");
                    var usages = new ArrayList<String>();
                    var usage = cert.getKeyUsage();
                    if (usage != null)
                        for (int i = 0; i < Math.min(usage.length, names.size()); i++)
                            if (usage[i]) usages.add(names.get(i));
                    result.put("keyUsages", usages);
                    try {
                        cert.checkValidity();
                        notExpired = true;
                    } catch (java.security.cert.CertificateException invalid) {
                    }
                    try {
                        result.put(
                                "certPathLength",
                                OfflineCertificateTrust.validate(cert, embedded, custom));
                        trusted = true;
                    } catch (Exception untrusted) {
                        result.put(
                                "chainValidationError",
                                "No valid path to a local or selected trust anchor at the current time; online checks are disabled.");
                    }
                }
                result.put("notExpired", notExpired);
                result.put("trustValid", trusted);
                result.put("chainValid", trusted);
                result.put("revocationChecked", false);
                result.put("revocationStatus", "not-checked");
                result.put("signerName", signature.getName() == null ? "" : signature.getName());
                results.add(result);
            }
        }
        return results;
    }

    /** Deliberate signature removal flattens signature appearances into a new unsigned output. */
    public static void removeSignatures(org.apache.pdfbox.pdmodel.PDDocument document)
            throws IOException {
        var form = document.getDocumentCatalog().getAcroForm();
        if (form != null) {
            var fields = new ArrayList<org.apache.pdfbox.pdmodel.interactive.form.PDField>();
            for (var field : form.getFieldTree())
                if (field instanceof org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField)
                    fields.add(field);
            if (!fields.isEmpty()) form.flatten(fields, false);
        }
    }
}

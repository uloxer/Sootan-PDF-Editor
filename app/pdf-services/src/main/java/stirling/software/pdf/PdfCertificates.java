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
        try (var document = Loader.loadPDF(input.toFile());
                var stream = Files.newOutputStream(output)) {
            if (document.isEncrypted())
                throw new IOException("Protected signing is not connected.");
            var signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName(((X509Certificate) chain[0]).getSubjectX500Principal().getName());
            signature.setReason(reason);
            signature.setSignDate(Calendar.getInstance());
            document.addSignature(
                    signature,
                    content -> {
                        try {
                            return DetachedCmsSigner.sign(content, key, chain, null).getEncoded();
                        } catch (Exception ex) {
                            throw new IOException("Certificate signing failed.", ex);
                        }
                    });
            document.saveIncremental(stream);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    public static List<Map<String, Object>> integrity(Path input) throws Exception {
        byte[] bytes = Files.readAllBytes(input);
        var results = new ArrayList<Map<String, Object>>();
        try (var document = Loader.loadPDF(bytes)) {
            for (var signature : document.getSignatureDictionaries()) {
                var result = new LinkedHashMap<String, Object>();
                var cms =
                        new CMSSignedData(
                                new CMSProcessableByteArray(signature.getSignedContent(bytes)),
                                new java.io.ByteArrayInputStream(signature.getContents(bytes)));
                boolean valid = true;
                for (var signer : cms.getSignerInfos().getSigners()) {
                    var matches = cms.getCertificates().getMatches(signer.getSID());
                    if (matches.isEmpty()) {
                        valid = false;
                        continue;
                    }
                    valid &=
                            signer.verify(
                                    new JcaSimpleSignerInfoVerifierBuilder()
                                            .build(
                                                    (X509CertificateHolder)
                                                            matches.iterator().next()));
                }
                int[] range = signature.getByteRange();
                result.put("valid", valid && !cms.getSignerInfos().getSigners().isEmpty());
                result.put(
                        "coversEntireDocument",
                        range.length == 4 && (long) range[2] + range[3] == bytes.length);
                result.put("trustValid", false);
                result.put("chainValid", false);
                result.put(
                        "chainValidationError",
                        "This worker verifies signature integrity only; trust-chain and revocation checks are not connected.");
                result.put("revocationChecked", false);
                result.put("revocationStatus", "not-checked");
                result.put("signerName", signature.getName() == null ? "" : signature.getName());
                results.add(result);
            }
        }
        return results;
    }
}

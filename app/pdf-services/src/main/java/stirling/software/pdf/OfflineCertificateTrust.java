package stirling.software.pdf;

import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Validates only locally supplied chains and roots; no AIA, OCSP, CRL or remote list loading. */
public final class OfflineCertificateTrust {
    private OfflineCertificateTrust() {}

    /**
     * The explicit selected certificate is an additional trust anchor, never an implicit embedded
     * root.
     */
    public static int validate(
            X509Certificate leaf, List<X509Certificate> embedded, X509Certificate selected)
            throws Exception {
        leaf.checkValidity();
        boolean[] usage = leaf.getKeyUsage();
        if (usage != null && !usage[0] && (usage.length < 2 || !usage[1]))
            throw new CertificateException("Signer key usage does not permit signing.");
        var roots = new HashSet<X509Certificate>();
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init((KeyStore) null);
        for (var manager : managers.getTrustManagers())
            if (manager instanceof X509TrustManager trust)
                roots.addAll(Arrays.asList(trust.getAcceptedIssuers()));
        if (selected != null) {
            selected.checkValidity();
            roots.add(selected);
        }
        List<X509Certificate> candidates = new ArrayList<>(embedded);
        candidates.addAll(roots);
        var chain = find(leaf, candidates, roots, new HashSet<>(), 0);
        if (chain == null)
            throw new CertificateException(
                    "No path to a local or explicitly selected trust anchor.");
        X509Certificate anchor = chain.removeLast();
        var parameters = new PKIXParameters(Set.of(new TrustAnchor(anchor, null)));
        parameters.setRevocationEnabled(false);
        if (!chain.isEmpty())
            CertPathValidator.getInstance("PKIX")
                    .validate(
                            CertificateFactory.getInstance("X.509").generateCertPath(chain),
                            parameters);
        return chain.size();
    }

    private static List<X509Certificate> find(
            X509Certificate cert,
            List<X509Certificate> candidates,
            Set<X509Certificate> roots,
            Set<X509Certificate> visited,
            int depth) {
        if (depth > 10 || !visited.add(cert)) return null;
        if (roots.contains(cert)) return new ArrayList<>(List.of(cert));
        for (var parent : candidates) {
            if (!cert.getIssuerX500Principal().equals(parent.getSubjectX500Principal())) continue;
            try {
                parent.checkValidity();
                if (parent.getBasicConstraints() < 0) continue;
                boolean[] usage = parent.getKeyUsage();
                if (usage != null && (usage.length <= 5 || !usage[5])) continue;
                cert.verify(parent.getPublicKey());
                var rest = find(parent, candidates, roots, new HashSet<>(visited), depth + 1);
                if (rest != null) {
                    rest.addFirst(cert);
                    return rest;
                }
            } catch (Exception invalid) {
                continue;
            }
        }
        return null;
    }
}

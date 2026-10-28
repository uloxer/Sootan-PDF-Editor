/*
 * Copyright 2015 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stirling.software.pdf;

import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;

import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

public final class DetachedCmsSigner {
    private DetachedCmsSigner() {}

    public static CMSSignedData sign(
            InputStream content,
            PrivateKey privateKey,
            Certificate[] certificateChain,
            Provider signingProvider)
            throws GeneralSecurityException, OperatorCreationException, CMSException {
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        X509Certificate cert = (X509Certificate) certificateChain[0];
        JcaContentSignerBuilder signerBuilder =
                new JcaContentSignerBuilder(resolveSignatureAlgorithm(privateKey, cert));
        // Hardware keys (PKCS#11 / Windows store) must sign on their own provider so the
        // operation runs on the token; software keys use the default provider.
        if (signingProvider != null) {
            signerBuilder.setProvider(signingProvider);
        }
        ContentSigner signer = signerBuilder.build(privateKey);
        gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                        .build(signer, cert));
        gen.addCertificates(new JcaCertStore(Arrays.asList(certificateChain)));
        PdfSignatureContent msg = new PdfSignatureContent(content);
        CMSSignedData signedData = gen.generate(msg, false);
        return signedData;
    }

    private static String resolveSignatureAlgorithm(PrivateKey key, X509Certificate cert) {
        String alg = key.getAlgorithm();
        if (alg == null || alg.isBlank()) {
            alg = cert.getPublicKey().getAlgorithm();
        }
        alg = alg == null ? "" : alg.toUpperCase(Locale.ROOT);
        if (alg.contains("ED25519") || alg.contains("EDDSA")) {
            return "Ed25519";
        }
        if (alg.contains("EC")) { // EC, ECDSA
            return "SHA256withECDSA";
        }
        if (alg.contains("DSA")) {
            return "SHA256withDSA";
        }
        return "SHA256withRSA";
    }
}

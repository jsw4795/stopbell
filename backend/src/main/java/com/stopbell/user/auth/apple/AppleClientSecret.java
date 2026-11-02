package com.stopbell.user.auth.apple;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Generates an Apple client secret from externally supplied PKCS#8 key material. */
public final class AppleClientSecret {
    private final String clientId;
    private final String teamId;
    private final String keyId;
    private final ECPrivateKey privateKey;
    private final Clock clock;

    public AppleClientSecret(String clientId, String teamId, String keyId, String privateKeyPem, Clock clock) {
        if (!valid(clientId) || !valid(teamId) || !valid(keyId) || privateKeyPem == null
                || privateKeyPem.length() > 8192) throw new IllegalArgumentException("Invalid Apple client configuration");
        this.clientId = clientId;
        this.teamId = teamId;
        this.keyId = keyId;
        this.clock = clock;
        try {
            String encoded = privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            privateKey = (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded.getBytes(StandardCharsets.US_ASCII))));
            if (privateKey.getParams().getCurve().getField().getFieldSize() != 256) {
                throw new IllegalArgumentException("Invalid Apple client configuration");
            }
            new ECDSASigner(privateKey);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Apple client configuration");
        }
    }

    public String create() {
        try {
            var now = clock.instant();
            var claims = new JWTClaimsSet.Builder().issuer(teamId).subject(clientId)
                    .audience("https://appleid.apple.com").issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300))).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId).build(), claims);
            jwt.sign(new ECDSASigner(privateKey));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("Apple client secret generation failed");
        }
    }

    private static boolean valid(String value) {
        return value != null && !value.isBlank() && value.length() <= 255;
    }
}

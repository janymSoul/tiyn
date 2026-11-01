package kz.tiyn.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public final class TestTokens {

    public static final String SECRET = "test-secret-test-secret-test-secret-0123";

    private TestTokens() {}

    public static String valid(UUID userId, String role) {
        return sign(
                SECRET,
                JWSAlgorithm.HS256,
                "tiyn",
                userId.toString(),
                role,
                Instant.now().plusSeconds(600));
    }

    public static String sign(
            String secret, JWSAlgorithm alg, String issuer, String subject, String role, Instant expiresAt) {
        try {
            var claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .claim("role", role)
                    .expirationTime(expiresAt == null ? null : Date.from(expiresAt))
                    .build();
            var jwt = new SignedJWT(new JWSHeader(alg), claims);
            jwt.sign(new MACSigner(secret.getBytes()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}

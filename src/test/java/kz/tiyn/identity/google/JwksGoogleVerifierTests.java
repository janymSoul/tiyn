package kz.tiyn.identity.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.util.Date;
import java.util.List;
import kz.tiyn.identity.domain.IdentityError;
import kz.tiyn.identity.domain.IdentityException;
import org.junit.jupiter.api.Test;

class JwksGoogleVerifierTests {

    private static final String UNREACHABLE_JWKS = "http://localhost:1/certs";

    private final JwksGoogleVerifier verifier =
            new JwksGoogleVerifier(new GoogleProperties(List.of("client")), UNREACHABLE_JWKS);

    @Test
    void aMalformedTokenIsAnInvalidIdentity() {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOfSatisfying(
                        IdentityException.class, e -> assertThat(e.error()).isEqualTo(IdentityError.INVALID_IDENTITY));
    }

    @Test
    void anUnreachableKeySetIsAProviderOutageNotABadCredential() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("k1").generate();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(),
                new JWTClaimsSet.Builder()
                        .issuer("https://accounts.google.com")
                        .subject("s")
                        .audience("client")
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());
        jwt.sign(new RSASSASigner(key));

        assertThatThrownBy(() -> verifier.verify(jwt.serialize()))
                .isInstanceOfSatisfying(IdentityException.class, e -> assertThat(e.error())
                        .isEqualTo(IdentityError.IDENTITY_PROVIDER_UNAVAILABLE));
    }
}

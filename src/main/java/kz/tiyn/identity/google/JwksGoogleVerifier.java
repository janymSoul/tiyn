package kz.tiyn.identity.google;

import java.util.List;
import kz.tiyn.identity.domain.IdentityError;
import kz.tiyn.identity.domain.IdentityException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

@Component
class JwksGoogleVerifier implements GoogleVerifier {

    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";
    private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    private final NimbusJwtDecoder decoder;

    JwksGoogleVerifier(GoogleProperties properties) {
        this.decoder = NimbusJwtDecoder.withJwkSetUri(JWKS_URI).build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(), issuer(), audience(properties.clientIds()), subject()));
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        try {
            Jwt jwt = decoder.decode(idToken);
            return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("name"));
        } catch (JwtException e) {
            throw new IdentityException(IdentityError.INVALID_IDENTITY);
        }
    }

    private static OAuth2TokenValidator<Jwt> issuer() {
        return jwt -> ISSUERS.contains(jwt.getClaimAsString("iss")) ? ok() : fail("unexpected issuer");
    }

    private static OAuth2TokenValidator<Jwt> audience(List<String> clientIds) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(clientIds::contains)
                ? ok()
                : fail("unexpected audience");
    }

    private static OAuth2TokenValidator<Jwt> subject() {
        return jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank() ? ok() : fail("missing subject");
    }

    private static OAuth2TokenValidatorResult ok() {
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult fail(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}

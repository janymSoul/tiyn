package kz.tiyn.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class PlatformSecurityTests {

    private static final String OTHER_SECRET = "another-secret-another-secret-0123456";

    @Autowired
    MockMvc mvc;

    @Test
    void missingTokenIsUnauthenticated() throws Exception {
        expectError(mvc.perform(get("/me")), 401, "unauthenticated");
    }

    @Test
    void validTokenPassesAuthenticationAndUnknownRouteIsNotFound() throws Exception {
        String token = TestTokens.valid(UUID.randomUUID(), "TEEN");

        expectError(mvc.perform(get("/nothing-here").header("Authorization", "Bearer " + token)), 404, "not_found");
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        String token = token(
                OTHER_SECRET, JWSAlgorithm.HS256, "tiyn", UUID.randomUUID().toString(), "TEEN", future());

        expectUnauthenticated(token);
    }

    @Test
    void tokenSignedWithAnotherAlgorithmIsRejected() throws Exception {
        String token = token(
                TestTokens.SECRET + TestTokens.SECRET,
                JWSAlgorithm.HS512,
                "tiyn",
                UUID.randomUUID().toString(),
                "TEEN",
                future());

        expectUnauthenticated(token);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String token = token(
                TestTokens.SECRET,
                JWSAlgorithm.HS256,
                "tiyn",
                UUID.randomUUID().toString(),
                "TEEN",
                Instant.now().minusSeconds(120));

        expectUnauthenticated(token);
    }

    @Test
    void tokenWithoutExpiryIsRejected() throws Exception {
        String token = token(
                TestTokens.SECRET, JWSAlgorithm.HS256, "tiyn", UUID.randomUUID().toString(), "TEEN", null);

        expectUnauthenticated(token);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        String token = token(
                TestTokens.SECRET, JWSAlgorithm.HS256, "evil", UUID.randomUUID().toString(), "TEEN", future());

        expectUnauthenticated(token);
    }

    @Test
    void tokenWithUnknownRoleIsRejected() throws Exception {
        String token = token(
                TestTokens.SECRET, JWSAlgorithm.HS256, "tiyn", UUID.randomUUID().toString(), "ADMIN", future());

        expectUnauthenticated(token);
    }

    @Test
    void tokenWithNonUuidSubjectIsRejected() throws Exception {
        String token = token(TestTokens.SECRET, JWSAlgorithm.HS256, "tiyn", "not-a-uuid", "TEEN", future());

        expectUnauthenticated(token);
    }

    private void expectUnauthenticated(String token) throws Exception {
        expectError(mvc.perform(get("/me").header("Authorization", "Bearer " + token)), 401, "unauthenticated");
    }

    private void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error").value(code));
    }

    private static String token(
            String secret, JWSAlgorithm alg, String issuer, String subject, String role, Instant expiresAt) {
        return TestTokens.sign(secret, alg, issuer, subject, role, expiresAt);
    }

    private static Instant future() {
        return Instant.now().plusSeconds(600);
    }
}

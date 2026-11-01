package kz.tiyn.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.platform.security.AccessTokenIssuer;
import kz.tiyn.platform.security.Role;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class AccessTokenIssuerTests {

    @Autowired
    AccessTokenIssuer issuer;

    @Autowired
    JwtDecoder decoder;

    @Autowired
    MockMvc mvc;

    @Test
    void issuedTokenCarriesTheAgreedClaimsAndLivesForTheConfiguredTtl() {
        UUID userId = UUID.randomUUID();

        var jwt = decoder.decode(issuer.issue(userId, Role.PARENT));

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("PARENT");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("tiyn");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(168));
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void issuedTokenIsAcceptedByTheSecurityFilter() throws Exception {
        String token = issuer.issue(UUID.randomUUID(), Role.TEEN);

        mvc.perform(get("/nothing-here").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}

package kz.tiyn.identity.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class RefreshTokenPurgeTests {

    @Autowired
    RefreshTokenPurgeTicker ticker;

    @Autowired
    JdbcTemplate jdbc;

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO identity.users (id, idp_sub, role, display_name) VALUES (?, ?, 'TEEN', 'Dana')",
                id,
                "sub-" + id);
        return id;
    }

    private void token(UUID user, String hash, String expiresIn) {
        jdbc.update(
                "INSERT INTO identity.refresh_tokens (id, user_id, family_id, token_hash, expires_at) VALUES (?, ?, ?, ?, now() + ?::interval)",
                UUID.randomUUID(),
                user,
                UUID.randomUUID(),
                hash,
                expiresIn);
    }

    @Test
    void expiredTokensArePurgedLiveOnesKeptAndRunningTwiceIsHarmless() {
        UUID user = user();
        token(user, "expired-" + user, "-1 hour");
        token(user, "live-" + user, "1 hour");

        ticker.tick(Instant.now());
        ticker.tick(Instant.now());

        assertThat(jdbc.queryForList(
                        "SELECT token_hash FROM identity.refresh_tokens WHERE user_id = ?", String.class, user))
                .containsExactly("live-" + user);
    }
}

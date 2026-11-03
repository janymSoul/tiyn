package kz.tiyn.identity.family;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import org.junit.jupiter.api.Test;

class JoinAttemptsTests {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    private final JoinAttempts attempts = JoinAttempts.restore(UUID.randomUUID(), 0, START);

    @Test
    void allowsUpToTheLimitThenRejectsInsideTheWindow() {
        for (int i = 0; i < JoinAttempts.MAX_ATTEMPTS; i++) {
            attempts.register(START.plusSeconds(i));
        }

        assertThatThrownBy(() -> attempts.register(START.plusSeconds(60)))
                .isInstanceOf(IdentityException.class)
                .extracting(e -> ((IdentityException) e).error())
                .isEqualTo(IdentityError.JOIN_RATE_LIMITED);
        assertThat(attempts.attempts()).isEqualTo(JoinAttempts.MAX_ATTEMPTS);
    }

    @Test
    void startsAFreshWindowOnceTheCurrentOneHasElapsed() {
        for (int i = 0; i < JoinAttempts.MAX_ATTEMPTS; i++) {
            attempts.register(START);
        }
        Instant later = START.plus(JoinAttempts.WINDOW);

        attempts.register(later);

        assertThat(attempts.attempts()).isEqualTo(1);
        assertThat(attempts.windowStartedAt()).isEqualTo(later);
    }
}

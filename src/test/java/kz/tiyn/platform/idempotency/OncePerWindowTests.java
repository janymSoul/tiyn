package kz.tiyn.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class OncePerWindowTests {

    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired
    OncePerWindow guard;

    @Test
    void theSecondAttemptInsideTheWindowIsRefused() {
        UUID user = UUID.randomUUID();

        assertThat(guard.tryAcquire(user, "export", HOUR)).isTrue();
        assertThat(guard.tryAcquire(user, "export", HOUR)).isFalse();
        assertThat(guard.tryAcquire(user, "export", HOUR)).isFalse();
    }

    @Test
    void usersAndOperationsAreIndependent() {
        UUID user = UUID.randomUUID();
        assertThat(guard.tryAcquire(user, "export", HOUR)).isTrue();

        assertThat(guard.tryAcquire(UUID.randomUUID(), "export", HOUR)).isTrue();
        assertThat(guard.tryAcquire(user, "other", HOUR)).isTrue();
    }

    @Test
    void aWindowThatHasPassedLetsTheNextAttemptThroughAndRestartsTheClock() {
        UUID user = UUID.randomUUID();
        assertThat(guard.tryAcquire(user, "export", HOUR)).isTrue();

        assertThat(guard.tryAcquire(user, "export", Duration.ZERO)).isTrue();
        assertThat(guard.tryAcquire(user, "export", HOUR)).isFalse();
    }
}

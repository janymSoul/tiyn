package kz.tiyn.identity.family;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;

final class JoinAttempts {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final UUID teenId;
    private int attempts;
    private Instant windowStartedAt;

    private JoinAttempts(UUID teenId, int attempts, Instant windowStartedAt) {
        this.teenId = teenId;
        this.attempts = attempts;
        this.windowStartedAt = windowStartedAt;
    }

    static JoinAttempts restore(UUID teenId, int attempts, Instant windowStartedAt) {
        return new JoinAttempts(teenId, attempts, windowStartedAt);
    }

    void register(Instant now) {
        if (!now.isBefore(windowStartedAt.plus(WINDOW))) {
            attempts = 0;
            windowStartedAt = now;
        }
        if (attempts >= MAX_ATTEMPTS) {
            throw new IdentityException(IdentityError.JOIN_RATE_LIMITED);
        }
        attempts++;
    }

    UUID teenId() {
        return teenId;
    }

    int attempts() {
        return attempts;
    }

    Instant windowStartedAt() {
        return windowStartedAt;
    }
}

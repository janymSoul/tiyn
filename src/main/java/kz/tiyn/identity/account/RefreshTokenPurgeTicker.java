package kz.tiyn.identity.account;

import java.time.Duration;
import java.time.Instant;
import kz.tiyn.platform.tick.Ticker;
import org.springframework.stereotype.Component;

@Component
class RefreshTokenPurgeTicker implements Ticker {

    private static final int BATCH = 1000;

    private final RefreshTokenStore tokens;

    RefreshTokenPurgeTicker(RefreshTokenStore tokens) {
        this.tokens = tokens;
    }

    @Override
    public Duration interval() {
        return Duration.ofHours(1);
    }

    @Override
    public void tick(Instant now) {
        int deleted;
        do {
            deleted = tokens.deleteExpired(now, BATCH);
        } while (deleted == BATCH);
    }
}

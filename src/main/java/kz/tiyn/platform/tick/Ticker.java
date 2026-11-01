package kz.tiyn.platform.tick;

import java.time.Instant;

public interface Ticker {

    void tick(Instant now);
}

package kz.tiyn.platform.tick;

import java.time.Duration;
import java.time.Instant;

public interface Ticker {

    void tick(Instant now);

    default String name() {
        return getClass().getSimpleName();
    }

    default Duration interval() {
        return Duration.ofMinutes(1);
    }

    default Duration lockAtMostFor() {
        return Duration.ofMinutes(5);
    }
}

package kz.tiyn.platform.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class Instants {

    private Instants() {}

    public static OffsetDateTime toUtc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromUtc(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

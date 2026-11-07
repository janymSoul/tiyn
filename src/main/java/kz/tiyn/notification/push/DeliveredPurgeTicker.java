package kz.tiyn.notification.push;

import java.time.Duration;
import java.time.Instant;
import kz.tiyn.platform.tick.Ticker;
import org.springframework.stereotype.Component;

@Component
class DeliveredPurgeTicker implements Ticker {

    private static final int BATCH = 1000;

    private final DeliveredStore delivered;
    private final NotificationProperties properties;

    DeliveredPurgeTicker(DeliveredStore delivered, NotificationProperties properties) {
        this.delivered = delivered;
        this.properties = properties;
    }

    @Override
    public Duration interval() {
        return Duration.ofHours(1);
    }

    @Override
    public void tick(Instant now) {
        Instant cutoff = now.minus(properties.deliveredRetention());
        int deleted;
        do {
            deleted = delivered.deleteOlderThan(cutoff, BATCH);
        } while (deleted == BATCH);
    }
}

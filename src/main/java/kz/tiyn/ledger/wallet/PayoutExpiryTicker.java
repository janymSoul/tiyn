package kz.tiyn.ledger.wallet;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kz.tiyn.platform.tick.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class PayoutExpiryTicker implements Ticker {

    private static final Logger log = LoggerFactory.getLogger(PayoutExpiryTicker.class);
    private static final int BATCH = 100;

    private final PayoutStore payouts;
    private final PayoutService service;
    private final LedgerProperties properties;

    PayoutExpiryTicker(PayoutStore payouts, PayoutService service, LedgerProperties properties) {
        this.payouts = payouts;
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void tick(Instant now) {
        Instant cutoff = now.minus(properties.payoutExpiry());
        List<UUID> batch;
        do {
            batch = payouts.findStaleIds(cutoff, BATCH);
            int expired = 0;
            for (UUID id : batch) {
                try {
                    if (service.expire(id, now)) {
                        expired++;
                    }
                } catch (RuntimeException e) {
                    log.error("payout {} could not be expired", id, e);
                }
            }
            if (expired == 0) {
                return;
            }
        } while (batch.size() == BATCH);
    }
}

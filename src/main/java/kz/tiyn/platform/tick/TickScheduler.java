package kz.tiyn.platform.tick;

import java.time.Clock;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class TickScheduler {

    private static final Logger log = LoggerFactory.getLogger(TickScheduler.class);

    private final List<Ticker> tickers;
    private final Clock clock;

    TickScheduler(List<Ticker> tickers, Clock clock) {
        this.tickers = tickers;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${tiyn.tick.interval}")
    @SchedulerLock(name = "tick", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    void runTickers() {
        var now = clock.instant();
        for (Ticker ticker : tickers) {
            try {
                ticker.tick(now);
            } catch (RuntimeException e) {
                log.error("ticker {} failed", ticker.getClass().getSimpleName(), e);
            }
        }
    }
}

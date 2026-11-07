package kz.tiyn.platform.tick;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tiyn.tick.enabled", havingValue = "true", matchIfMissing = true)
class TickRegistrar implements SchedulingConfigurer, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TickRegistrar.class);
    private static final String LOCK_PREFIX = "tick:";
    private static final Duration LOCK_MARGIN = Duration.ofSeconds(5);
    private static final int POOL_SIZE = 4;
    private static final int AWAIT_TERMINATION_SECONDS = 25;

    private final List<Ticker> tickers;
    private final LockingTaskExecutor locks;
    private final Clock clock;
    private ThreadPoolTaskScheduler scheduler;

    TickRegistrar(List<Ticker> tickers, LockProvider lockProvider, Clock clock) {
        this.tickers = tickers;
        this.locks = new DefaultLockingTaskExecutor(lockProvider);
        this.clock = clock;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("tick-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
        requireUniqueNames();
        for (Ticker ticker : tickers) {
            registrar.addFixedDelayTask(() -> run(ticker), ticker.interval());
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private static Duration lockAtLeastFor(Ticker ticker) {
        return ticker.interval().compareTo(LOCK_MARGIN) > 0 ? ticker.interval().minus(LOCK_MARGIN) : Duration.ZERO;
    }

    private void requireUniqueNames() {
        var seen = new HashSet<String>();
        for (Ticker ticker : tickers) {
            if (!seen.add(ticker.name())) {
                throw new IllegalStateException("two tickers are named " + ticker.name() + " and would share a lock");
            }
        }
    }

    private void run(Ticker ticker) {
        try {
            Duration lockAtLeastFor = lockAtLeastFor(ticker);
            Duration lockAtMostFor =
                    ticker.lockAtMostFor().compareTo(lockAtLeastFor) > 0 ? ticker.lockAtMostFor() : lockAtLeastFor;
            locks.executeWithLock(
                    (Runnable) () -> ticker.tick(clock.instant()),
                    new LockConfiguration(clock.instant(), LOCK_PREFIX + ticker.name(), lockAtMostFor, lockAtLeastFor));
        } catch (RuntimeException e) {
            log.error("ticker {} failed", ticker.name(), e);
        }
    }
}

package kz.tiyn.platform.tick;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class TickRegistrarTests {

    private final RecordingLocks locks = new RecordingLocks();

    @Test
    void registersOneLockedTaskPerTickerWithItsOwnNameAndInterval() {
        var fast = new CountingTicker();
        var slow = new SlowTicker();
        var registrar = registrarFor(fast, slow);

        assertThat(registrar.getFixedDelayTaskList())
                .extracting(IntervalTask::getIntervalDuration)
                .containsExactly(Duration.ofMinutes(1), Duration.ofHours(1));
        registrar.getFixedDelayTaskList().forEach(task -> task.getRunnable().run());

        assertThat(fast.ticks.get()).isEqualTo(1);
        assertThat(slow.ticks.get()).isEqualTo(1);
        assertThat(locks.requested)
                .extracting(LockConfiguration::getName, LockConfiguration::getLockAtMostFor)
                .containsExactly(
                        tuple("tick:CountingTicker", Duration.ofMinutes(5)),
                        tuple("tick:SlowTicker", Duration.ofMinutes(20)));
        assertThat(locks.requested)
                .allSatisfy(config -> assertThat(config.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void aThrowingTickerDoesNotStopTheOthers() {
        var counting = new CountingTicker();
        var registrar = registrarFor(new ThrowingTicker(), counting);

        registrar
                .getFixedDelayTaskList()
                .forEach(task -> assertThatCode(() -> task.getRunnable().run()).doesNotThrowAnyException());

        assertThat(counting.ticks.get()).isEqualTo(1);
    }

    @Test
    void aTickerWhoseLockIsHeldElsewhereDoesNotRun() {
        locks.held = true;
        var counting = new CountingTicker();
        var registrar = registrarFor(counting);

        registrar.getFixedDelayTaskList().forEach(task -> task.getRunnable().run());

        assertThat(counting.ticks.get()).isZero();
    }

    @Test
    void refusesTwoTickersWithTheSameNameBecauseTheyWouldShareALock() {
        assertThatThrownBy(() -> registrarFor(new CountingTicker(), new CountingTicker()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CountingTicker");
    }

    @Test
    void registersNothingWhenTickingIsDisabled() {
        contextRunner().withPropertyValues("tiyn.tick.enabled=false").run(context -> assertThat(context)
                .doesNotHaveBean(TickRegistrar.class));
    }

    @Test
    void ticksRunOnTheirOwnScheduleThroughTheSpringScheduler() {
        var fast = new CountingTicker(Duration.ofMillis(20));
        var other = new CountingTicker(Duration.ofMillis(20)) {
            @Override
            public String name() {
                return "Other";
            }
        };

        contextRunner()
                .withUserConfiguration(EnableSchedulingConfig.class)
                .withBean("fast", Ticker.class, () -> fast)
                .withBean("other", Ticker.class, () -> other)
                .run(context -> {
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                        assertThat(fast.ticks.get()).isGreaterThanOrEqualTo(2);
                        assertThat(other.ticks.get()).isGreaterThanOrEqualTo(2);
                    });
                    assertThat(locks.requested)
                            .extracting(LockConfiguration::getName)
                            .contains("tick:CountingTicker", "tick:Other");
                });
    }

    private ScheduledTaskRegistrar registrarFor(Ticker... tickers) {
        var registrar = new ScheduledTaskRegistrar();
        new TickRegistrar(List.of(tickers), locks, Clock.systemUTC()).configureTasks(registrar);
        return registrar;
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(TickRegistrar.class)
                .withBean(LockProvider.class, () -> locks)
                .withBean(Clock.class, Clock::systemUTC);
    }

    @Configuration
    @EnableScheduling
    static class EnableSchedulingConfig {}

    static class CountingTicker implements Ticker {

        final AtomicInteger ticks = new AtomicInteger();
        private final Duration interval;

        CountingTicker() {
            this(Duration.ofMinutes(1));
        }

        CountingTicker(Duration interval) {
            this.interval = interval;
        }

        @Override
        public Duration interval() {
            return interval;
        }

        @Override
        public void tick(Instant now) {
            ticks.incrementAndGet();
        }
    }

    static class SlowTicker extends CountingTicker {

        SlowTicker() {
            super(Duration.ofHours(1));
        }

        @Override
        public Duration lockAtMostFor() {
            return Duration.ofMinutes(20);
        }
    }

    static class ThrowingTicker implements Ticker {

        @Override
        public void tick(Instant now) {
            throw new IllegalStateException("boom");
        }
    }

    static class RecordingLocks implements LockProvider {

        final List<LockConfiguration> requested = new CopyOnWriteArrayList<>();
        volatile boolean held;

        @Override
        public Optional<SimpleLock> lock(LockConfiguration configuration) {
            requested.add(configuration);
            return held ? Optional.empty() : Optional.of(() -> {});
        }
    }
}

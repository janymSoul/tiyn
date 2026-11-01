package kz.tiyn.platform.tick;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskAssigned;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "tiyn.events.resubmit-after=PT0S")
@Import(EventDurabilityTests.FlakyConfig.class)
class EventDurabilityTests {

    static class FlakyListener {

        private final Set<UUID> failedOnce = ConcurrentHashMap.newKeySet();
        private final List<UUID> deliveries = new CopyOnWriteArrayList<>();
        private final List<UUID> effects = new CopyOnWriteArrayList<>();

        List<UUID> deliveries() {
            return deliveries;
        }

        List<UUID> effects() {
            return effects;
        }

        @ApplicationModuleListener
        void on(TaskAssigned event) {
            deliveries.add(event.taskId());
            if (failedOnce.add(event.taskId())) {
                throw new IllegalStateException("simulated listener failure");
            }
            effects.add(event.taskId());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlakyConfig {

        @Bean
        FlakyListener flakyListener() {
            return new FlakyListener();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FlakyListener listener;

    @Autowired
    EventResubmitTicker ticker;

    private int incompleteFor(UUID taskId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM event_publication WHERE completion_date IS NULL "
                        + "AND listener_id LIKE '%FlakyListener%' AND serialized_event LIKE ?",
                Integer.class, "%" + taskId + "%");
    }

    @Test
    void aListenerThatFailsOnceIsRedeliveredByTheResubmitTickerAndTheEffectHappensOnce() throws Exception {
        Family family = new TaskFixtures(mvc, json).linkedFamily();

        UUID taskId = new TaskFixtures(mvc, json).createTask(family, 100);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(listener.deliveries()).contains(taskId));
        assertThat(listener.effects()).doesNotContain(taskId);
        assertThat(incompleteFor(taskId)).isEqualTo(1);

        ticker.tick(Instant.now());

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(listener.effects()).containsOnlyOnce(taskId));
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(incompleteFor(taskId)).isZero());
        assertThat(listener.deliveries().stream().filter(taskId::equals)).hasSize(2);

        ticker.tick(Instant.now());
        assertThat(listener.effects()).containsOnlyOnce(taskId);
        assertThat(Map.of(
                        "deliveries",
                        listener.deliveries().stream().filter(taskId::equals).count()))
                .containsEntry("deliveries", 2L);
    }
}

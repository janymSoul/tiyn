package kz.tiyn.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class DeliveredPurgeTests {

    @Autowired
    DeliveredPurgeTicker ticker;

    @Autowired
    JdbcTemplate jdbc;

    private int rows(UUID recipient) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification.delivered WHERE recipient_id = ?", Integer.class, recipient);
    }

    private void insert(UUID recipient, String key, String age) {
        jdbc.update(
                "INSERT INTO notification.delivered (event_key, recipient_id, delivered_at) VALUES (?, ?, now() - ?::interval)",
                key,
                recipient,
                age);
    }

    @Test
    void oldRecordsArePurgedRecentOnesKeptAndRunningTwiceIsHarmless() {
        UUID recipient = UUID.randomUUID();
        insert(recipient, "old-1", "40 days");
        insert(recipient, "old-2", "31 days");
        insert(recipient, "recent", "29 days");

        ticker.tick(Instant.now());
        ticker.tick(Instant.now());

        assertThat(jdbc.queryForList(
                        "SELECT event_key FROM notification.delivered WHERE recipient_id = ?", String.class, recipient))
                .containsExactly("recent");
    }
}

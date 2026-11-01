package kz.tiyn.ledger.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class PayoutLifecycleTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    BalanceReader balances;

    @Autowired
    PayoutExpiryTicker ticker;

    @Autowired
    PayoutService service;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private UUID openPayout(Family f) throws Exception {
        UUID task = fx.createTask(f, 500_00);
        fx.send("/tasks/" + task + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "s-" + task)
                .andExpect(status().isOk());
        fx.send("/tasks/" + task + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(500_00));
        String body = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":30000}";
        String response = fx.send("/payouts", body, f.teen().bearer(), "Idempotency-Key", "k-" + task)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private String stateOf(UUID id) {
        return jdbc.queryForObject("SELECT state FROM ledger.payouts WHERE id = ?", String.class, id);
    }

    private String reasonOf(UUID id) {
        return jdbc.queryForObject("SELECT cancel_reason FROM ledger.payouts WHERE id = ?", String.class, id);
    }

    @Test
    void aRequestNobodyAnswersExpiresOnceAndFreesTheBalance() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = openPayout(f);

        ticker.tick(Instant.now().plus(Duration.ofDays(6)));
        assertThat(stateOf(id)).isEqualTo("REQUESTED");

        ticker.tick(Instant.now().plus(Duration.ofDays(8)));
        ticker.tick(Instant.now().plus(Duration.ofDays(9)));

        assertThat(stateOf(id)).isEqualTo("CANCELLED");
        assertThat(reasonOf(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM ledger.payouts WHERE id = ?", UUID.class, id))
                .isNull();
        assertThat(balances.balance(f.teen().id()).availableAmount()).isEqualTo(500_00);
    }

    @Test
    void aFulfilledPayoutIsNeverExpired() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = openPayout(f);
        fx.send("/payouts/" + id + "/fulfil", "", f.parent().bearer()).andExpect(status().isOk());

        ticker.tick(Instant.now().plus(Duration.ofDays(30)));

        assertThat(stateOf(id)).isEqualTo("FULFILLED");
    }

    @Test
    void unlinkingCancelsTheOpenRequest() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = openPayout(f);

        mvc.perform(delete("/links/" + f.teen().id())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(stateOf(id)).isEqualTo("CANCELLED"));
        assertThat(reasonOf(id)).isEqualTo("UNLINKED");
        assertThat(balances.balance(f.teen().id()).availableAmount()).isEqualTo(500_00);
    }

    @Test
    void aReplayedUnlinkEventWhileStillLinkedChangesNothing() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = openPayout(f);

        service.cancelAfterUnlink(f.parent().id(), f.teen().id());

        assertThat(stateOf(id)).isEqualTo("REQUESTED");
    }
}

package kz.tiyn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class WalletOverviewReaderTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    WalletOverviewReader overviews;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private Family richFamily(long reward) throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, reward);
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "s-" + id)
                .andExpect(status().isOk());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                        overviews.overview(f.teen().id()).balance().balanceAmount())
                .isEqualTo(reward));
        return f;
    }

    private UUID createGoal(Family f, String name, long target) throws Exception {
        String response = fx.send(
                        "/goals",
                        "{\"name\":\"" + name + "\",\"targetAmount\":" + target + "}",
                        f.teen().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    @Test
    void aTeenWithNoActivityGetsZerosAndNothingElse() throws Exception {
        Family f = fx.linkedFamily();

        WalletOverview overview = overviews.overview(f.teen().id());

        assertThat(overview.balance().balanceAmount()).isZero();
        assertThat(overview.balance().availableAmount()).isZero();
        assertThat(overview.goals()).isEmpty();
        assertThat(overview.openPayout()).isNull();
    }

    @Test
    void activeGoalsShowTheirSavedAmountAndClosedOnesDisappear() throws Exception {
        Family f = richFamily(15_000_00);
        UUID kept = createGoal(f, "Headphones", 10_000_00);
        UUID closed = createGoal(f, "Bike", 50_000_00);
        fx.send("/goals/" + kept + "/lock", "{\"amount\":500000}", f.teen().bearer(), "Idempotency-Key", "a")
                .andExpect(status().isOk());
        fx.send("/goals/" + closed + "/close", "", f.teen().bearer()).andExpect(status().isOk());

        WalletOverview overview = overviews.overview(f.teen().id());

        assertThat(overview.goals()).hasSize(1);
        assertThat(overview.goals().get(0).id()).isEqualTo(kept);
        assertThat(overview.goals().get(0).savedAmount()).isEqualTo(500_000L);
        assertThat(overview.balance().lockedAmount()).isEqualTo(500_000L);
    }

    @Test
    void anOpenPayoutRequestShowsAndLowersAvailable() throws Exception {
        Family f = richFamily(500_00);

        fx.send(
                        "/payouts",
                        "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":30000}",
                        f.teen().bearer(),
                        "Idempotency-Key",
                        "p1")
                .andExpect(status().isCreated());

        WalletOverview overview = overviews.overview(f.teen().id());

        assertThat(overview.openPayout()).isNotNull();
        assertThat(overview.openPayout().amount()).isEqualTo(30_000);
        assertThat(overview.openPayout().parentId()).isEqualTo(f.parent().id());
        assertThat(overview.balance().availableAmount()).isEqualTo(20_000);
    }
}

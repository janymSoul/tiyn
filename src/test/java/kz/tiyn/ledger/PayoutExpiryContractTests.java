package kz.tiyn.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "tiyn.ledger.payout-expiry=PT36H")
class PayoutExpiryContractTests {

    private static final Duration CONFIGURED = Duration.ofHours(36);

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

    private JsonNode body(String path, String bearer) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private static void assertExpiresAfterConfiguredExpiry(JsonNode payout) {
        assertThat(Instant.parse(payout.get("expiresAt").asText()))
                .isEqualTo(Instant.parse(payout.get("requestedAt").asText()).plus(CONFIGURED));
    }

    private UUID openPayout(Family f) throws Exception {
        UUID task = fx.createTask(f, 500_00);
        fx.send("/tasks/" + task + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "s-" + task)
                .andExpect(status().isOk());
        fx.send("/tasks/" + task + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                        overviews.overview(f.teen().id()).balance().balanceAmount())
                .isEqualTo(500_00));
        String created = fx.send(
                        "/payouts",
                        "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":30000}",
                        f.teen().bearer(),
                        "Idempotency-Key",
                        "p-" + task)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertExpiresAfterConfiguredExpiry(json.readTree(created));
        return UUID.fromString(json.readTree(created).get("id").asText());
    }

    @Test
    void everyViewOfAnOpenPayoutFollowsTheConfiguredExpiry() throws Exception {
        Family f = fx.linkedFamily();
        UUID payout = openPayout(f);

        assertExpiresAfterConfiguredExpiry(body("/payouts/" + payout, f.teen().bearer()));
        assertExpiresAfterConfiguredExpiry(
                body("/payouts", f.parent().bearer()).get("items").get(0));
        assertExpiresAfterConfiguredExpiry(body("/dashboard", f.teen().bearer()).get("openPayout"));
        assertThat(overviews.overview(f.teen().id()).openPayout().expiresAt())
                .isEqualTo(Instant.parse(body("/payouts/" + payout, f.teen().bearer())
                                .get("requestedAt")
                                .asText())
                        .plus(CONFIGURED));
    }
}

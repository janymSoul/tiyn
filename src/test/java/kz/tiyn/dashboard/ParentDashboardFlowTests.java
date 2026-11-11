package kz.tiyn.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import kz.tiyn.task.TaskFixtures.Unjoined;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class ParentDashboardFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    BalanceReader balances;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private ResultActions dashboard(String bearer) throws Exception {
        return mvc.perform(get("/parent/dashboard").header("Authorization", bearer));
    }

    private void submit(Family f, UUID id) throws Exception {
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "k-" + id)
                .andExpect(status().isOk());
    }

    private void approve(Family f, UUID id) throws Exception {
        submit(f, id);
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
    }

    private void awaitBalance(Person teen, long amount) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() ->
                        assertThat(balances.balance(teen.id()).balanceAmount()).isEqualTo(amount));
    }

    private Person joinSecondTeen(Family f, String joinCode) throws Exception {
        Person second = fx.unlinkedTeen();
        fx.join(new Unjoined(new Family(f.parent(), second), joinCode));
        return second;
    }

    private void requestPayout(Family f, long amount) throws Exception {
        fx.send(
                        "/payouts",
                        "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":" + amount + "}",
                        f.teen().bearer(),
                        "Idempotency-Key",
                        "p-" + UUID.randomUUID())
                .andExpect(status().isCreated());
    }

    @Test
    void aParentWithTwoTeensSeesEachTeensBalanceReviewsAndPayout() throws Exception {
        Unjoined unjoined = fx.unjoinedFamily();
        fx.join(unjoined);
        Family first = unjoined.family();
        Person secondTeen = joinSecondTeen(first, unjoined.joinCode());
        Family second = new Family(first.parent(), secondTeen);

        approve(first, fx.createTask(first, 1_500_000));
        awaitBalance(first.teen(), 1_500_000);
        UUID waitingA = fx.createTask(first, 700);
        UUID waitingB = fx.createTask(first, 200);
        fx.createTask(first, 50);
        submit(first, waitingA);
        submit(first, waitingB);
        requestPayout(first, 300_000);
        UUID waitingOfSecond = fx.createTask(second, 900);
        submit(second, waitingOfSecond);

        dashboard(first.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPendingReviews").value(3))
                .andExpect(jsonPath("$.totalOpenPayouts").value(1))
                .andExpect(jsonPath("$.teens.length()").value(2))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].displayName")
                        .value("Dana"))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].balance.balanceAmount")
                        .value(1_500_000))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].balance.availableAmount")
                        .value(1_200_000))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].balance.currency")
                        .value("KZT"))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].pendingReviewCount")
                        .value(2))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].pendingReviews[0].id")
                        .value(waitingB.toString()))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].pendingReviews[1].id")
                        .value(waitingA.toString()))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].pendingReviews[0].rewardAmount")
                        .value(200))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].pendingReviews[0].title")
                        .value("Wash dishes"))
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].pendingReviews[0].submittedAt")
                        .exists())
                .andExpect(jsonPath("$.teens[?(@.id=='" + first.teen().id() + "')].openPayout.amount")
                        .value(300_000))
                .andExpect(jsonPath("$.teens[?(@.id=='" + secondTeen.id() + "')].pendingReviewCount")
                        .value(1))
                .andExpect(jsonPath("$.teens[?(@.id=='" + secondTeen.id() + "')].pendingReviews[0].id")
                        .value(waitingOfSecond.toString()))
                .andExpect(jsonPath("$.teens[?(@.id=='" + secondTeen.id() + "')].balance.balanceAmount")
                        .value(0))
                .andExpect(jsonPath("$.teens[?(@.id=='" + secondTeen.id() + "')].openPayout")
                        .isEmpty());
    }

    @Test
    void thePreviewIsCappedButTheCountIsNot() throws Exception {
        Family f = fx.linkedFamily();
        UUID last = null;
        for (int i = 0; i < 12; i++) {
            last = fx.createTask(f, 100 + i);
            submit(f, last);
        }

        dashboard(f.parent().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPendingReviews").value(12))
                .andExpect(jsonPath("$.teens[0].pendingReviewCount").value(12))
                .andExpect(jsonPath("$.teens[0].pendingReviews.length()").value(10))
                .andExpect(jsonPath("$.teens[0].pendingReviews[0].id").value(last.toString()));
    }

    @Test
    void aDecidedTaskLeavesTheReviewQueue() throws Exception {
        Family f = fx.linkedFamily();
        approve(f, fx.createTask(f, 100));

        dashboard(f.parent().bearer())
                .andExpect(jsonPath("$.totalPendingReviews").value(0))
                .andExpect(jsonPath("$.teens[0].pendingReviewCount").value(0))
                .andExpect(jsonPath("$.teens[0].pendingReviews.length()").value(0));
    }

    @Test
    void aParentWithoutTeensGetsAnEmptyList() throws Exception {
        Person parent = fx.unjoinedFamily().family().parent();

        dashboard(parent.bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPendingReviews").value(0))
                .andExpect(jsonPath("$.totalOpenPayouts").value(0))
                .andExpect(jsonPath("$.teens.length()").value(0));
    }

    @Test
    void anUnlinkedTeenDisappearsFromTheDashboard() throws Exception {
        Unjoined unjoined = fx.unjoinedFamily();
        fx.join(unjoined);
        Family first = unjoined.family();
        Person secondTeen = joinSecondTeen(first, unjoined.joinCode());
        submit(new Family(first.parent(), secondTeen), fx.createTask(new Family(first.parent(), secondTeen), 100));

        dashboard(first.parent().bearer())
                .andExpect(jsonPath("$.teens.length()").value(2))
                .andExpect(jsonPath("$.totalPendingReviews").value(1));

        mvc.perform(delete("/links/" + secondTeen.id())
                        .header("Authorization", first.parent().bearer()))
                .andExpect(status().isNoContent());

        dashboard(first.parent().bearer())
                .andExpect(jsonPath("$.teens.length()").value(1))
                .andExpect(jsonPath("$.teens[0].id").value(first.teen().id().toString()))
                .andExpect(jsonPath("$.totalPendingReviews").value(0));
    }

    @Test
    void teensAreForbiddenAndAnonymousCallersAreUnauthorised() throws Exception {
        Family f = fx.linkedFamily();

        dashboard(f.teen().bearer()).andExpect(status().isForbidden());
        mvc.perform(get("/parent/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void aParentNeverSeesAnotherParentsTeen() throws Exception {
        Family mine = fx.linkedFamily();
        Family other = fx.linkedFamily();
        approve(other, fx.createTask(other, 500_000));
        awaitBalance(other.teen(), 500_000);
        submit(other, fx.createTask(other, 900));
        requestPayout(other, 100_000);

        dashboard(mine.parent().bearer())
                .andExpect(jsonPath("$.teens.length()").value(1))
                .andExpect(jsonPath("$.teens[0].id").value(mine.teen().id().toString()))
                .andExpect(jsonPath("$.teens[0].pendingReviewCount").value(0))
                .andExpect(jsonPath("$.totalPendingReviews").value(0))
                .andExpect(jsonPath("$.totalOpenPayouts").value(0));
        dashboard(other.parent().bearer())
                .andExpect(jsonPath("$.teens.length()").value(1))
                .andExpect(jsonPath("$.teens[0].id").value(other.teen().id().toString()))
                .andExpect(jsonPath("$.totalPendingReviews").value(1))
                .andExpect(jsonPath("$.totalOpenPayouts").value(1));
    }
}

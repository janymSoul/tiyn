package kz.tiyn.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class DashboardFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    BalanceReader balances;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private ResultActions dashboard(String bearer) throws Exception {
        return mvc.perform(get("/dashboard").header("Authorization", bearer));
    }

    private void submit(Family f, UUID id) throws Exception {
        fx.send("/tasks/" + id + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "k-" + id)
                .andExpect(status().isOk());
    }

    private void approve(Family f, UUID id) throws Exception {
        submit(f, id);
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
    }

    private void awaitBalance(Family f, long amount) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(amount));
    }

    @Test
    void aNewTeenSeesZerosAndEmptyLists() throws Exception {
        Family f = fx.linkedFamily();

        dashboard(f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.balanceAmount").value(0))
                .andExpect(jsonPath("$.balance.availableAmount").value(0))
                .andExpect(jsonPath("$.balance.currency").value("KZT"))
                .andExpect(jsonPath("$.pendingAmount").value(0))
                .andExpect(jsonPath("$.chores.length()").value(0))
                .andExpect(jsonPath("$.milestones.length()").value(0))
                .andExpect(jsonPath("$.goals.length()").value(0))
                .andExpect(jsonPath("$.openPayout").doesNotExist());
    }

    @Test
    void aSubmittedTaskIsPendingAndAnApprovedOneIsPaid() throws Exception {
        Family f = fx.linkedFamily();
        UUID waiting = fx.createTask(f, 700);
        UUID paid = fx.createTask(f, 300);
        submit(f, waiting);
        approve(f, paid);
        awaitBalance(f, 300);

        dashboard(f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.balanceAmount").value(300))
                .andExpect(jsonPath("$.pendingAmount").value(700))
                .andExpect(jsonPath("$.chores.length()").value(2))
                .andExpect(
                        jsonPath("$.chores[?(@.id=='" + waiting + "')].state").value("SUBMITTED"))
                .andExpect(jsonPath("$.chores[?(@.id=='" + paid + "')].state").value("APPROVED"));
    }

    @Test
    void milestonesShowProgressAndTheirTasksPointAtThem() throws Exception {
        Family f = fx.linkedFamily();
        UUID milestone = fx.createItem(f, "MILESTONE", null, 86_400);
        UUID first = fx.createItem(f, "TASK", milestone, 3600);
        fx.createItem(f, "TASK", milestone, 3600);
        approve(f, first);

        dashboard(f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.milestones.length()").value(1))
                .andExpect(jsonPath("$.milestones[0].id").value(milestone.toString()))
                .andExpect(jsonPath("$.milestones[0].approvedTasks").value(1))
                .andExpect(jsonPath("$.milestones[0].totalTasks").value(2))
                .andExpect(jsonPath("$.chores.length()").value(2))
                .andExpect(jsonPath("$.chores[0].milestoneId").value(milestone.toString()))
                .andExpect(jsonPath("$.chores[0].approvedTasks").doesNotExist());
    }

    @Test
    void goalsAndTheOpenPayoutAreIncluded() throws Exception {
        Family f = fx.linkedFamily();
        UUID task = fx.createTask(f, 1_500_000);
        approve(f, task);
        awaitBalance(f, 1_500_000);
        String goal = json.readTree(fx.send(
                                "/goals",
                                "{\"name\":\"Headphones\",\"targetAmount\":1000000}",
                                f.teen().bearer())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asText();
        fx.send("/goals/" + goal + "/lock", "{\"amount\":500000}", f.teen().bearer(), "Idempotency-Key", "g1")
                .andExpect(status().isOk());
        fx.send(
                        "/payouts",
                        "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":300000}",
                        f.teen().bearer(),
                        "Idempotency-Key",
                        "p1")
                .andExpect(status().isCreated());

        dashboard(f.teen().bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.lockedAmount").value(500_000))
                .andExpect(jsonPath("$.balance.availableAmount").value(700_000))
                .andExpect(jsonPath("$.goals.length()").value(1))
                .andExpect(jsonPath("$.goals[0].name").value("Headphones"))
                .andExpect(jsonPath("$.goals[0].savedAmount").value(500_000))
                .andExpect(jsonPath("$.openPayout.amount").value(300_000))
                .andExpect(
                        jsonPath("$.openPayout.parentId").value(f.parent().id().toString()))
                .andExpect(jsonPath("$.openPayout.expiresAt").exists());
    }

    @Test
    void oldDecidedTasksFallOutOfTheDashboard() throws Exception {
        Family f = fx.linkedFamily();
        UUID old = fx.createTask(f, 100);
        UUID fresh = fx.createTask(f, 100);
        approve(f, old);
        approve(f, fresh);
        jdbc.update("update task.tasks set decided_at = now() - interval '30 days' where id = ?", old);

        dashboard(f.teen().bearer())
                .andExpect(jsonPath("$.chores.length()").value(1))
                .andExpect(jsonPath("$.chores[0].id").value(fresh.toString()));
    }

    @Test
    void parentsAreForbiddenAndAnonymousCallersAreUnauthorised() throws Exception {
        Family f = fx.linkedFamily();

        dashboard(f.parent().bearer()).andExpect(status().isForbidden());
        mvc.perform(get("/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTeenSeesOnlyTheirOwnData() throws Exception {
        Family mine = fx.linkedFamily();
        Family other = fx.linkedFamily();
        fx.createTask(other, 900);

        dashboard(mine.teen().bearer())
                .andExpect(jsonPath("$.chores.length()").value(0))
                .andExpect(jsonPath("$.pendingAmount").value(0));
    }
}

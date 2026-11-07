package kz.tiyn.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.ledger.PayoutRequested;
import kz.tiyn.platform.tick.Ticker;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import kz.tiyn.task.TaskFixtures.Unjoined;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@AutoConfigureMockMvc
@Import(RecordingPushSender.Config.class)
class NotificationFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    DeviceStore devices;

    @Autowired
    RecordingPushSender sender;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    BalanceReader balances;

    @Autowired
    List<Ticker> tickers;

    @Autowired
    IncompleteEventPublications incompletePublications;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private String device(Person who, String locale) {
        String token = "tok-" + UUID.randomUUID();
        devices.upsert(who.id(), token, Platform.ANDROID, locale, Instant.now());
        return token;
    }

    private RecordingPushSender.Sent awaitPush(String token, String type) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(sender.sentTo(token, type)).hasSize(1));
        return sender.sentTo(token, type).get(0);
    }

    private void assertNoPush(String token, String type) {
        await().during(Duration.ofMillis(700))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(sender.sentTo(token, type)).isEmpty());
    }

    private UUID createTask(Family f, long reward, long penalty) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("teenId", f.teen().id());
        body.put("title", "Wash dishes");
        body.put("rewardAmount", reward);
        body.put("penaltyAmount", penalty);
        body.put("currency", "KZT");
        body.put("deadlineAt", Instant.now().plusSeconds(3600).toString());
        String response = fx.send(
                        "/tasks", json.writeValueAsString(body), f.parent().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private void submit(Family f, UUID task) throws Exception {
        fx.send("/tasks/" + task + "/submit", "{\"text\":\"done\"}", f.teen().bearer(), "Idempotency-Key", "k-" + task)
                .andExpect(status().isOk());
    }

    private void earn(Family f, long reward) throws Exception {
        long before = balances.balance(f.teen().id()).balanceAmount();
        UUID task = createTask(f, reward, 0);
        submit(f, task);
        fx.send("/tasks/" + task + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(balances.balance(f.teen().id()).balanceAmount())
                        .isEqualTo(before + reward));
    }

    private UUID requestPayout(Family f, long amount, String key) throws Exception {
        String body = "{\"parentId\":\"" + f.parent().id() + "\",\"amount\":" + amount + "}";
        String response = fx.send("/payouts", body, f.teen().bearer(), "Idempotency-Key", key)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    @Test
    void theTaskLifecycleNotifiesTheRightSide() throws Exception {
        Family f = fx.linkedFamily();
        String teen = device(f.teen(), "ru");
        String parent = device(f.parent(), "ru");

        UUID task = createTask(f, 500_00, 0);
        var assigned = awaitPush(teen, "TASK_ASSIGNED");
        assertThat(assigned.push().body()).contains("500");
        assertThat(assigned.push().data()).containsEntry("taskId", task.toString());

        submit(f, task);
        awaitPush(parent, "TASK_SUBMITTED");
        assertNoPush(teen, "TASK_SUBMITTED");

        fx.send("/tasks/" + task + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        assertThat(awaitPush(teen, "TASK_APPROVED").push().body()).contains("500");
    }

    @Test
    void aMilestonePushCarriesTheMilestoneOwnId() throws Exception {
        Family f = fx.linkedFamily();
        String teen = device(f.teen(), "ru");
        String parent = device(f.parent(), "ru");

        UUID milestone = fx.createItem(f, "MILESTONE", null, 7200);
        assertThat(awaitPush(teen, "TASK_ASSIGNED").push().data()).containsEntry("taskId", milestone.toString());

        UUID child = fx.createItem(f, "TASK", milestone, 3600);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(sender.sentTo(teen, "TASK_ASSIGNED"))
                .extracting(s -> s.push().data().get("taskId"))
                .containsExactlyInAnyOrder(milestone.toString(), child.toString()));

        submit(f, milestone);
        assertThat(awaitPush(parent, "TASK_SUBMITTED").push().data()).containsEntry("taskId", milestone.toString());

        fx.send("/tasks/" + milestone + "/approve", "", f.parent().bearer()).andExpect(status().isOk());
        assertThat(awaitPush(teen, "TASK_APPROVED").push().data()).containsEntry("taskId", milestone.toString());
    }

    @Test
    void rejectionRedoAndCancellationGoToTheTeenWithTheNote() throws Exception {
        Family f = fx.linkedFamily();
        String teen = device(f.teen(), "ru");

        UUID rejected = createTask(f, 100_00, 0);
        submit(f, rejected);
        fx.send(
                        "/tasks/" + rejected + "/reject",
                        "{\"note\":\"Not clean enough\"}",
                        f.parent().bearer())
                .andExpect(status().isOk());
        assertThat(awaitPush(teen, "TASK_REJECTED").push().body()).contains("Not clean enough");

        UUID redo = createTask(f, 100_00, 0);
        submit(f, redo);
        fx.send(
                        "/tasks/" + redo + "/request-redo",
                        "{\"note\":\"Do the pans\"}",
                        f.parent().bearer())
                .andExpect(status().isOk());
        assertThat(awaitPush(teen, "TASK_REDO_REQUESTED").push().body()).contains("Do the pans");

        UUID cancelled = createTask(f, 100_00, 0);
        fx.send("/tasks/" + cancelled + "/cancel", "", f.parent().bearer()).andExpect(status().isOk());
        awaitPush(teen, "TASK_CANCELLED");
    }

    @Test
    void aMissedTaskTellsTheTeenAboutTheDeadlineAndTheDeduction() throws Exception {
        Family f = fx.linkedFamily();
        String teen = device(f.teen(), "ru");
        createTask(f, 100_00, 150_00);

        tickers.forEach(t -> t.tick(Instant.now().plusSeconds(7200)));

        assertThat(awaitPush(teen, "TASK_EXPIRED_WITH_PENALTY").push().body()).contains("150");
        assertThat(awaitPush(teen, "PENALTY_APPLIED").push().body()).contains("150");
    }

    @Test
    void aMissedTaskWithoutAPenaltyOnlyMentionsTheDeadline() throws Exception {
        Family f = fx.linkedFamily();
        String teen = device(f.teen(), "ru");
        createTask(f, 100_00, 0);

        tickers.forEach(t -> t.tick(Instant.now().plusSeconds(7200)));

        awaitPush(teen, "TASK_EXPIRED");
        assertNoPush(teen, "PENALTY_APPLIED");
    }

    @Test
    void payoutEventsGoToTheRightPerson() throws Exception {
        Family f = fx.linkedFamily();
        String teen = device(f.teen(), "ru");
        String parent = device(f.parent(), "ru");
        earn(f, 1000_00);

        UUID paid = requestPayout(f, 100_00, "p1");
        var request = awaitPush(parent, "PAYOUT_REQUESTED");
        assertThat(request.push().highPriority()).isTrue();
        assertThat(request.push().data()).containsEntry("payoutId", paid.toString());
        fx.send("/payouts/" + paid + "/fulfil", "", f.parent().bearer()).andExpect(status().isOk());
        awaitPush(teen, "PAYOUT_FULFILLED");

        UUID cancelled = requestPayout(f, 100_00, "p2");
        fx.send("/payouts/" + cancelled + "/cancel", "", f.teen().bearer()).andExpect(status().isOk());
        assertThat(awaitPush(parent, "PAYOUT_CANCELLED_BY_TEEN").push().body())
                .contains("100")
                .doesNotContain("{");
        assertNoPush(teen, "PAYOUT_CANCELLED_BY_TEEN");

        UUID declined = requestPayout(f, 100_00, "p3");
        fx.send("/payouts/" + declined + "/decline", "", f.parent().bearer()).andExpect(status().isOk());
        assertThat(awaitPush(teen, "PAYOUT_DECLINED").push().body())
                .contains("100")
                .doesNotContain("{");
    }

    @Test
    void linkingWelcomesBothSidesAndUnlinkingTellsTheTeen() throws Exception {
        Unjoined unjoined = fx.unjoinedFamily();
        Family f = unjoined.family();
        String teen = device(f.teen(), "ru");
        String parent = device(f.parent(), "ru");

        fx.join(unjoined);

        awaitPush(parent, "FAMILY_LINKED_PARENT");
        awaitPush(teen, "FAMILY_LINKED_TEEN");

        mvc.perform(delete("/links/" + f.teen().id())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());

        awaitPush(teen, "FAMILY_UNLINKED");
        assertNoPush(parent, "FAMILY_UNLINKED");
    }

    @Test
    void aFailingTeenDeviceDoesNotResendTheParentWelcomeOnRedelivery() throws Exception {
        Unjoined unjoined = fx.unjoinedFamily();
        Family f = unjoined.family();
        String teen = device(f.teen(), "ru");
        String parent = device(f.parent(), "ru");
        sender.failWith(teen, new IllegalStateException("fcm unavailable"));

        fx.join(unjoined);
        awaitPush(parent, "FAMILY_LINKED_PARENT");

        for (int attempt = 0; attempt < 3; attempt++) {
            incompletePublications.resubmitIncompletePublicationsOlderThan(Duration.ZERO);
        }
        assertThat(sender.sentTo(teen)).isEmpty();
        assertNoPush(parent, "FAMILY_LINKED_TEEN");
        assertThat(sender.sentTo(parent, "FAMILY_LINKED_PARENT")).hasSize(1);

        sender.stopFailing(teen);
        incompletePublications.resubmitIncompletePublicationsOlderThan(Duration.ZERO);
        awaitPush(teen, "FAMILY_LINKED_TEEN");
        assertThat(sender.sentTo(parent, "FAMILY_LINKED_PARENT")).hasSize(1);
    }

    @Test
    void theCopyFollowsEachDevicesLanguage() throws Exception {
        Family f = fx.linkedFamily();
        String ru = device(f.teen(), "ru");
        String kk = device(f.teen(), "kk");

        createTask(f, 500_00, 0);

        assertThat(awaitPush(ru, "TASK_ASSIGNED").push().title()).isEqualTo("Новое задание");
        assertThat(awaitPush(kk, "TASK_ASSIGNED").push().title()).isEqualTo("Жаңа тапсырма");
    }

    @Test
    void goalActivityAndARedeliveredEventSendNothingExtra() throws Exception {
        Family f = fx.linkedFamily();
        String parent = device(f.parent(), "ru");
        earn(f, 1000_00);
        String goal = fx.send(
                        "/goals",
                        "{\"name\":\"Bike\",\"targetAmount\":50000}",
                        f.teen().bearer())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID goalId = UUID.fromString(json.readTree(goal).get("id").asText());
        fx.send("/goals/" + goalId + "/lock", "{\"amount\":10000}", f.teen().bearer(), "Idempotency-Key", "g1")
                .andExpect(status().isOk());

        PayoutRequested event =
                new PayoutRequested(UUID.randomUUID(), f.teen().id(), f.parent().id(), 100_00, "KZT");
        tx.executeWithoutResult(status -> {
            publisher.publishEvent(event);
            publisher.publishEvent(event);
        });

        awaitPush(parent, "PAYOUT_REQUESTED");
        assertThat(sender.sentTo(parent).stream().map(s -> s.push().data().get("type")))
                .doesNotContain("GOAL_LOCKED")
                .containsOnlyOnce("PAYOUT_REQUESTED");
    }
}

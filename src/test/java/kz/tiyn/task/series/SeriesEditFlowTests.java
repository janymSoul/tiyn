package kz.tiyn.task.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.platform.tx.ConflictException;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class SeriesEditFlowTests {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    SeriesTicker ticker;

    @Autowired
    SeriesStore store;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private static LocalDate today() {
        return LocalDate.now(ALMATY);
    }

    private static Instant at(LocalDate day, String time) {
        return ZonedDateTime.of(day, LocalTime.parse(time), ALMATY).toInstant();
    }

    private UUID createSeries(Family f, LocalDate startsOn) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("teenId", f.teen().id());
        body.put("title", "Walk dog");
        body.put("description", "Twice");
        body.put("rewardAmount", 100);
        body.put("penaltyAmount", 10);
        body.put("currency", "KZT");
        body.put("recurrence", "DAILY");
        body.put("dueTime", "23:59");
        body.put("startsOn", startsOn.toString());
        String response = fx.send(
                        "/task-series",
                        json.writeValueAsString(body),
                        f.parent().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private ResultActions patchAs(Person who, UUID id, Map<String, Object> changes) throws Exception {
        return mvc.perform(patch("/task-series/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", who.bearer())
                .content(json.writeValueAsString(changes)));
    }

    private int occurrences(UUID seriesId) {
        return jdbc.queryForObject("SELECT count(*) FROM task.tasks WHERE series_id = ?", Integer.class, seriesId);
    }

    private Map<String, Object> occurrenceOn(UUID seriesId, LocalDate date) {
        return jdbc.queryForMap(
                "SELECT title, reward_amount, deadline_at FROM task.tasks WHERE series_id = ? AND occurrence_date = ?",
                seriesId,
                date);
    }

    @Test
    void editingChangesFutureOccurrencesAndLeavesTasksAlreadyCreatedUntouched() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today());
        assertThat(occurrenceOn(series, today()).get("title")).isEqualTo("Walk dog");

        patchAs(f.parent(), series, Map.of("title", "Feed cat", "rewardAmount", 250, "dueTime", "22:15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(series.toString()))
                .andExpect(jsonPath("$.title").value("Feed cat"))
                .andExpect(jsonPath("$.rewardAmount").value(250))
                .andExpect(jsonPath("$.dueTime").value("22:15:00"))
                .andExpect(jsonPath("$.active").value(true));
        ticker.tick(at(today().plusDays(1), "10:00"));

        assertThat(occurrenceOn(series, today()).get("title")).isEqualTo("Walk dog");
        assertThat(((Number) occurrenceOn(series, today()).get("reward_amount")).longValue())
                .isEqualTo(100);
        Map<String, Object> next = occurrenceOn(series, today().plusDays(1));
        assertThat(next.get("title")).isEqualTo("Feed cat");
        assertThat(((Number) next.get("reward_amount")).longValue()).isEqualTo(250);
        assertThat(((Timestamp) next.get("deadline_at")).toInstant()).isEqualTo(at(today().plusDays(1), "22:15"));
    }

    @Test
    void absentFieldsStayAsTheyWere() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today().plusDays(2));

        patchAs(f.parent(), series, Map.of("description", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Walk dog"))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.rewardAmount").value(100))
                .andExpect(jsonPath("$.penaltyAmount").value(10))
                .andExpect(jsonPath("$.recurrence").value("DAILY"))
                .andExpect(jsonPath("$.startsOn").value(today().plusDays(2).toString()))
                .andExpect(jsonPath("$.timezone").value("Asia/Almaty"));
        patchAs(f.parent(), series, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Walk dog"));
    }

    @Test
    void recurrenceAndStartDateAreNotEditable() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today());

        patchAs(f.parent(), series, Map.of("recurrence", "WEEKLY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_body"));
        patchAs(f.parent(), series, Map.of("startsOn", "2030-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_body"));
    }

    @Test
    void pausingStopsNewOccurrencesAndResumingContinuesWithoutBackfillingMissedDays() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today().plusDays(1));

        patchAs(f.parent(), series, Map.of("active", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.closed").value(false));
        ticker.tick(at(today().plusDays(1), "10:00"));
        ticker.tick(at(today().plusDays(2), "10:00"));
        assertThat(occurrences(series)).isZero();

        patchAs(f.parent(), series, Map.of("active", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        ticker.tick(at(today().plusDays(3), "10:00"));

        assertThat(occurrences(series)).isEqualTo(1);
        assertThat(occurrenceOn(series, today().plusDays(3))).isNotEmpty();
    }

    @Test
    void aPausedSeriesAppearsAsStoppedInTheListAndKeepsExistingTasks() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today());

        patchAs(f.parent(), series, Map.of("active", false)).andExpect(status().isOk());

        mvc.perform(get("/task-series/" + series)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/task-series?active=false&teenId=" + f.teen().id())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.items[0].id").value(series.toString()));
        assertThat(occurrences(series)).isEqualTo(1);
    }

    @Test
    void pausingAndResumingAreRepeatableAndCanBeCombinedWithEdits() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today().plusDays(1));

        patchAs(f.parent(), series, Map.of("active", true))
                .andExpect(jsonPath("$.active").value(true));
        patchAs(f.parent(), series, Map.of("active", false))
                .andExpect(jsonPath("$.active").value(false));
        patchAs(f.parent(), series, Map.of("active", false, "title", "Renamed"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.title").value("Renamed"));
    }

    @Test
    void aClosedSeriesCannotBeEditedPausedOrResumed() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today().plusDays(1));
        patchAs(f.parent(), series, Map.of("active", false)).andExpect(status().isOk());
        mvc.perform(delete("/task-series/" + series)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());

        patchAs(f.parent(), series, Map.of("active", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
        patchAs(f.parent(), series, Map.of("title", "Late"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
        mvc.perform(get("/task-series/" + series)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.title").value("Walk dog"));
    }

    @Test
    void onlyTheOwningLinkedParentCanPatch() throws Exception {
        Family f = fx.linkedFamily();
        Family other = fx.linkedFamily();
        UUID series = createSeries(f, today());

        patchAs(other.parent(), series, Map.of("title", "Hijack")).andExpect(status().isForbidden());
        patchAs(f.teen(), series, Map.of("title", "Teen edit")).andExpect(status().isForbidden());
        jdbc.update(
                "UPDATE identity.family_links SET removed_at = now() WHERE parent_id = ? AND teen_id = ?",
                f.parent().id(),
                f.teen().id());
        patchAs(f.parent(), series, Map.of("title", "Unlinked")).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT title FROM task.task_series WHERE id = ?", String.class, series))
                .isEqualTo("Walk dog");
    }

    @Test
    void anUnknownSeriesIsNotFound() throws Exception {
        Family f = fx.linkedFamily();

        patchAs(f.parent(), UUID.randomUUID(), Map.of("title", "x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void valuesAreValidatedLikeOnCreationAndNothingIsSavedOnFailure() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today());

        patchAs(f.parent(), series, Map.of("rewardAmount", 0))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_reward"));
        patchAs(f.parent(), series, Map.of("penaltyAmount", -1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_penalty"));
        patchAs(f.parent(), series, Map.of("title", "  "))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_title"));
        patchAs(f.parent(), series, Map.of("timezone", "Mars/Base", "title", "Changed"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_schedule"));

        mvc.perform(get("/task-series/" + series)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(jsonPath("$.title").value("Walk dog"))
                .andExpect(jsonPath("$.rewardAmount").value(100));
    }

    @Test
    void anUnreadableTimeIsABadRequest() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, today());

        patchAs(f.parent(), series, Map.of("dueTime", "late"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_body"));
    }

    @Test
    void aStaleVersionIsRejectedInsteadOfOverwritingTheNewerChange() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = createSeries(f, today());
        TaskSeries first = store.find(id).orElseThrow();
        TaskSeries second = store.find(id).orElseThrow();
        first.pause(f.parent().id());
        store.saveVersioned(first);

        assertThatThrownBy(() -> store.saveVersioned(second)).isInstanceOf(ConflictException.class);
        assertThat(store.find(id).orElseThrow().isActive()).isFalse();
        assertThat(store.find(id).orElseThrow().version()).isEqualTo(1);
    }
}

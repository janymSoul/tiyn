package kz.tiyn.task.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
class SeriesFlowTests {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    SeriesTicker ticker;

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

    private String body(Family f, String recurrence, LocalDate startsOn) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("teenId", f.teen().id());
        body.put("title", "Walk dog");
        body.put("rewardAmount", 100);
        body.put("currency", "KZT");
        body.put("recurrence", recurrence);
        body.put("dueTime", "23:59");
        body.put("startsOn", startsOn == null ? null : startsOn.toString());
        return json.writeValueAsString(body);
    }

    private UUID createSeries(Family f, String recurrence, LocalDate startsOn) throws Exception {
        String response = fx.send(
                        "/task-series",
                        body(f, recurrence, startsOn),
                        f.parent().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    private int occurrences(UUID seriesId) {
        return jdbc.queryForObject("SELECT count(*) FROM task.tasks WHERE series_id = ?", Integer.class, seriesId);
    }

    @Test
    void creatingASeriesWithoutAKeyIsRefusedAndCreatesNothing() throws Exception {
        Family f = fx.linkedFamily();

        mvc.perform(post("/task-series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", f.parent().bearer())
                        .content(body(f, "DAILY", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));
    }

    @Test
    void repeatingACreateWithTheSameKeyReturnsTheSameSeriesWithoutANewOccurrence() throws Exception {
        Family f = fx.linkedFamily();
        String request = body(f, "DAILY", null);

        String first = fx.send("/task-series", request, f.parent().bearer(), "Idempotency-Key", "s-1")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID id = UUID.fromString(json.readTree(first).get("id").asText());
        fx.send("/task-series", request, f.parent().bearer(), "Idempotency-Key", "s-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
        fx.send("/task-series", body(f, "WEEKLY", null), f.parent().bearer(), "Idempotency-Key", "s-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_idempotency_key"));

        assertThat(occurrences(id)).isEqualTo(1);
    }

    @Test
    void creatingASeriesMaterialisesTodaysOccurrenceImmediately() throws Exception {
        Family f = fx.linkedFamily();

        UUID series = createSeries(f, "DAILY", null);

        assertThat(occurrences(series)).isEqualTo(1);
    }

    @Test
    void anOccurrenceNamesItsSeriesAndAPlainTaskOmitsTheField() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, "DAILY", null);
        UUID plain = fx.createTask(f, 100);
        UUID occurrence = jdbc.queryForObject("SELECT id FROM task.tasks WHERE series_id = ?", UUID.class, series);

        mvc.perform(get("/tasks/" + occurrence).header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesId").value(series.toString()));
        mvc.perform(get("/tasks/" + plain).header("Authorization", f.teen().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seriesId").doesNotExist());
        mvc.perform(get("/tasks").header("Authorization", f.parent().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + occurrence + "')].seriesId")
                        .value(series.toString()))
                .andExpect(
                        jsonPath("$.items[?(@.id == '" + plain + "')].seriesId").isEmpty());
    }

    @Test
    void dailyTicksAreIdempotentAndNeverBackfillMissedDays() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, "DAILY", null);

        ticker.tick(at(today().plusDays(1), "10:00"));
        ticker.tick(at(today().plusDays(1), "10:00"));
        ticker.tick(at(today().plusDays(1), "11:00"));
        assertThat(occurrences(series)).isEqualTo(2);

        ticker.tick(at(today().plusDays(3), "10:00"));
        assertThat(occurrences(series)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM task.tasks WHERE series_id = ? AND occurrence_date = ?",
                        Integer.class,
                        series,
                        today().plusDays(2)))
                .isZero();
    }

    @Test
    void weeklySeriesOnlyAddsAnOccurrenceOnItsWeekday() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, "WEEKLY", null);

        ticker.tick(at(today().plusDays(3), "10:00"));
        assertThat(occurrences(series)).isEqualTo(1);

        ticker.tick(at(today().plusDays(7), "10:00"));
        assertThat(occurrences(series)).isEqualTo(2);
    }

    @Test
    void aSeriesStartingInTheFutureHasNoOccurrenceUntilThen() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, "DAILY", today().plusDays(2));

        assertThat(occurrences(series)).isZero();
        ticker.tick(at(today().plusDays(1), "10:00"));
        assertThat(occurrences(series)).isZero();
        ticker.tick(at(today().plusDays(2), "10:00"));
        assertThat(occurrences(series)).isEqualTo(1);
    }

    @Test
    void closingStopsFurtherOccurrencesAndIsIdempotent() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, "DAILY", null);

        mvc.perform(delete("/task-series/" + series)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/task-series/" + series)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());
        ticker.tick(at(today().plusDays(1), "10:00"));

        assertThat(occurrences(series)).isEqualTo(1);
    }

    @Test
    void anUnlinkedPairGetsNoOccurrenceEvenBeforeTheSeriesIsClosed() throws Exception {
        Family f = fx.linkedFamily();
        UUID series = createSeries(f, "DAILY", null);
        jdbc.update(
                "UPDATE identity.family_links SET removed_at = now() WHERE parent_id = ? AND teen_id = ?",
                f.parent().id(),
                f.teen().id());
        jdbc.update("UPDATE task.task_series SET active = TRUE WHERE id = ?", series);

        ticker.tick(at(today().plusDays(1), "10:00"));

        assertThat(occurrences(series)).isEqualTo(1);
    }

    @Test
    void occurrencesAreOrdinaryTasksTheTeenCanSee() throws Exception {
        Family f = fx.linkedFamily();
        createSeries(f, "DAILY", null);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/tasks")
                        .header("Authorization", f.teen().bearer()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Walk dog"))
                .andExpect(jsonPath("$.items[0].state").value("ASSIGNED"));
    }

    @Test
    void authorisationAndValidation() throws Exception {
        Family f = fx.linkedFamily();
        Family other = fx.linkedFamily();
        UUID series = createSeries(f, "DAILY", null);

        fx.send("/task-series", body(f, "DAILY", null), f.teen().bearer()).andExpect(status().isForbidden());
        fx.send("/task-series", body(f, "DAILY", null), other.parent().bearer()).andExpect(status().isForbidden());
        mvc.perform(delete("/task-series/" + series)
                        .header("Authorization", other.parent().bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/task-series/" + UUID.randomUUID())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNotFound());
        fx.send("/task-series", body(f, "MONTHLY", null), f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_schedule"));
        fx.send(
                        "/task-series",
                        body(f, "DAILY", null).replace("\"rewardAmount\":100", "\"rewardAmount\":0"),
                        f.parent().bearer())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_reward"));
    }
}

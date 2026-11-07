package kz.tiyn.task.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import kz.tiyn.task.TaskFixtures.Person;
import kz.tiyn.task.TaskFixtures.Unjoined;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
class SeriesQueryFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private JsonNode createSeries(Person parent, UUID teenId, String title) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("teenId", teenId);
        body.put("title", title);
        body.put("rewardAmount", 100);
        body.put("currency", "KZT");
        body.put("recurrence", "DAILY");
        body.put("dueTime", "23:59");
        String response = fx.send("/task-series", json.writeValueAsString(body), parent.bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private ResultActions getAs(Person who, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", who.bearer()));
    }

    private JsonNode itemsAs(Person who, String path) throws Exception {
        return listAs(who, path).get("items");
    }

    private JsonNode listAs(Person who, String path) throws Exception {
        return json.readTree(getAs(who, path)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private static List<String> ids(JsonNode array) {
        return IntStream.range(0, array.size())
                .mapToObj(i -> array.get(i).get("id").asText())
                .toList();
    }

    private Person secondTeenOf(Family f) throws Exception {
        Person teen = fx.unlinkedTeen();
        String code = json.readTree(getAs(f.parent(), "/me")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .at("/joinCode")
                .asText();
        fx.join(new Unjoined(new Family(f.parent(), teen), code));
        return teen;
    }

    @Test
    void aParentListsTheirSeriesNewestFirstInTheShapeThatCreateReturned() throws Exception {
        Family f = fx.linkedFamily();
        JsonNode first = createSeries(f.parent(), f.teen().id(), "Walk dog");
        JsonNode second = createSeries(f.parent(), f.teen().id(), "Take out trash");

        JsonNode listed = itemsAs(f.parent(), "/task-series");

        assertThat(ids(listed))
                .containsExactly(second.get("id").asText(), first.get("id").asText());
        assertThat(listed.get(0)).isEqualTo(second);
        assertThat(listed.get(1)).isEqualTo(first);
    }

    @Test
    void getReturnsOneSeriesInTheCreateShape() throws Exception {
        Family f = fx.linkedFamily();
        JsonNode created = createSeries(f.parent(), f.teen().id(), "Walk dog");

        assertThat(listAs(f.parent(), "/task-series/" + created.get("id").asText()))
                .isEqualTo(created);
    }

    @Test
    void anotherParentsSeriesIsNotFoundAndNotListed() throws Exception {
        Family mine = fx.linkedFamily();
        Family theirs = fx.linkedFamily();
        JsonNode foreign = createSeries(theirs.parent(), theirs.teen().id(), "Not yours");
        JsonNode own = createSeries(mine.parent(), mine.teen().id(), "Mine");

        getAs(mine.parent(), "/task-series/" + foreign.get("id").asText())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        assertThat(ids(itemsAs(mine.parent(), "/task-series")))
                .containsExactly(own.get("id").asText());
    }

    @Test
    void anUnknownSeriesIsNotFound() throws Exception {
        Family f = fx.linkedFamily();

        getAs(f.parent(), "/task-series/" + UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void aTeenCannotReadSeries() throws Exception {
        Family f = fx.linkedFamily();
        JsonNode created = createSeries(f.parent(), f.teen().id(), "Walk dog");

        getAs(f.teen(), "/task-series").andExpect(status().isForbidden());
        getAs(f.teen(), "/task-series/" + created.get("id").asText())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void theTeenFilterNarrowsTheListToThatTeen() throws Exception {
        Family f = fx.linkedFamily();
        Person other = secondTeenOf(f);
        JsonNode forFirst = createSeries(f.parent(), f.teen().id(), "First teen");
        JsonNode forOther = createSeries(f.parent(), other.id(), "Other teen");

        assertThat(ids(itemsAs(f.parent(), "/task-series?teenId=" + f.teen().id())))
                .containsExactly(forFirst.get("id").asText());
        assertThat(ids(itemsAs(f.parent(), "/task-series?teenId=" + other.id())))
                .containsExactly(forOther.get("id").asText());
        assertThat(ids(itemsAs(f.parent(), "/task-series")))
                .containsExactly(forOther.get("id").asText(), forFirst.get("id").asText());
    }

    @Test
    void filteringByATeenThatIsNotLinkedIsForbidden() throws Exception {
        Family f = fx.linkedFamily();
        Person stranger = fx.unlinkedTeen();
        Family unrelated = fx.linkedFamily();

        getAs(f.parent(), "/task-series?teenId=" + stranger.id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        getAs(f.parent(), "/task-series?teenId=" + unrelated.teen().id())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
    }

    @Test
    void theActiveFilterSeparatesRunningFromStoppedSeries() throws Exception {
        Family f = fx.linkedFamily();
        JsonNode running = createSeries(f.parent(), f.teen().id(), "Running");
        JsonNode stopped = createSeries(f.parent(), f.teen().id(), "Stopped");
        mvc.perform(delete("/task-series/" + stopped.get("id").asText())
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());

        assertThat(ids(itemsAs(f.parent(), "/task-series?active=true")))
                .containsExactly(running.get("id").asText());
        assertThat(ids(itemsAs(f.parent(), "/task-series?active=false")))
                .containsExactly(stopped.get("id").asText());
        assertThat(ids(itemsAs(f.parent(), "/task-series"))).hasSize(2);
        assertThat(listAs(f.parent(), "/task-series/" + stopped.get("id").asText())
                        .get("active")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void aMalformedActiveFilterIsABadRequest() throws Exception {
        Family f = fx.linkedFamily();

        getAs(f.parent(), "/task-series?active=maybe")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }
}

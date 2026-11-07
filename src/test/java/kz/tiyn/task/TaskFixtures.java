package kz.tiyn.task;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

public final class TaskFixtures {

    public record Person(UUID id, String bearer) {}

    public record Family(Person parent, Person teen) {}

    private final MockMvc mvc;
    private final ObjectMapper json;

    public TaskFixtures(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public record Unjoined(Family family, String joinCode) {}

    public Unjoined unjoinedFamily() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        return new Unjoined(
                new Family(person(parent), person(teen)),
                parent.at("/user/joinCode").asText());
    }

    public void join(Unjoined unjoined) throws Exception {
        mvc.perform(post("/links/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", unjoined.family().teen().bearer())
                        .content("{\"code\":\"" + unjoined.joinCode() + "\"}"))
                .andReturn();
    }

    public Family linkedFamily() throws Exception {
        Unjoined unjoined = unjoinedFamily();
        join(unjoined);
        return unjoined.family();
    }

    public Person unlinkedTeen() throws Exception {
        return person(signIn("TEEN", "Stranger"));
    }

    public ResultActions send(String path, String body, String bearer, String... headers) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        boolean hasKey = false;
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
            hasKey |= headers[i].equalsIgnoreCase("Idempotency-Key");
        }
        if (path.equals("/tasks") && !hasKey) {
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        return mvc.perform(request);
    }

    public UUID createTask(Family family, long reward) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "teenId",
                family.teen().id(),
                "title",
                "Wash dishes",
                "rewardAmount",
                reward,
                "currency",
                "KZT",
                "deadlineAt",
                Instant.now().plusSeconds(3600).toString()));
        String response = send("/tasks", body, family.parent().bearer())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    public UUID createItem(Family family, String kind, UUID milestoneId, long deadlineInSeconds) throws Exception {
        String response = send(
                        "/tasks",
                        bodyFor(family, kind, milestoneId, deadlineInSeconds),
                        family.parent().bearer())
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    public String bodyFor(Family family, String kind, UUID milestoneId, long deadlineInSeconds) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("teenId", family.teen().id());
        body.put("kind", kind);
        body.put("title", "Item");
        body.put("rewardAmount", 100);
        body.put("currency", "KZT");
        body.put("deadlineAt", Instant.now().plusSeconds(deadlineInSeconds).toString());
        body.put("milestoneId", milestoneId);
        return json.writeValueAsString(body);
    }

    private JsonNode signIn(String role, String name) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of(
                "idToken", tokenFor("sub-" + UUID.randomUUID(), name), "role", role, "displayName", name));
        String response =
                send("/auth/google", body, null).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static Person person(JsonNode signIn) {
        return new Person(
                UUID.fromString(signIn.at("/user/id").asText()),
                "Bearer " + signIn.get("token").asText());
    }
}

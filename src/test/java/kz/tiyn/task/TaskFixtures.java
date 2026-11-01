package kz.tiyn.task;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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

    public Family linkedFamily() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        Person p = person(parent);
        Person t = person(teen);
        mvc.perform(post("/links/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", t.bearer())
                        .content("{\"code\":\"" + parent.at("/user/joinCode").asText() + "\"}"))
                .andReturn();
        return new Family(p, t);
    }

    public Person unlinkedTeen() throws Exception {
        return person(signIn("TEEN", "Stranger"));
    }

    public ResultActions send(String path, String body, String bearer, String... headers) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
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

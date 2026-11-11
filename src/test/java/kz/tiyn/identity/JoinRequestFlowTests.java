package kz.tiyn.identity;

import static kz.tiyn.support.FakeGoogleConfig.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class JoinRequestFlowTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    ApplicationEvents events;

    @Autowired
    FamilyDirectory directory;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void approvalIsOffByDefaultAndShownToParentsOnly() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        JsonNode teen = signIn("TEEN", "Dana");

        assertThat(parent.at("/user/joinApproval").asBoolean(true)).isFalse();
        assertThat(parent.at("/user/joinApproval").isBoolean()).isTrue();
        assertThat(teen.at("/user/joinApproval").isMissingNode()).isTrue();
        mvc.perform(get("/me").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.joinApproval").value(false));
        mvc.perform(get("/me").header("Authorization", bearer(teen)))
                .andExpect(jsonPath("$.joinApproval").doesNotExist());
    }

    @Test
    void aParentTurnsApprovalOnAndOffAndTheAccountShowsIt() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");

        setApproval(parent, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.joinApproval").value(true))
                .andExpect(
                        jsonPath("$.joinCode").value(parent.at("/user/joinCode").asText()));
        mvc.perform(get("/me").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.joinApproval").value(true));
        setApproval(parent, true).andExpect(status().isOk());
        setApproval(parent, false).andExpect(jsonPath("$.joinApproval").value(false));
        mvc.perform(get("/me").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.joinApproval").value(false));
    }

    @Test
    void aTeenCannotChangeApprovalAndABodyWithoutTheFlagIsRejected() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        JsonNode teen = signIn("TEEN", "Dana");

        setApproval(teen, true)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(put("/me/join-approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(parent))
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("validation_failed"));
        mvc.perform(put("/me/join-approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"required\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/me").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.joinApproval").value(false));
    }

    @Test
    void withApprovalOffJoiningStillLinksImmediately() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        JsonNode teen = signIn("TEEN", "Dana");

        join(teen, parent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.id").value(id(parent).toString()));

        assertThat(directory.areLinked(id(parent), id(teen))).isTrue();
        assertThat(requestCount(parent)).isZero();
    }

    @Test
    void withApprovalOnJoiningCreatesAPendingRequestAndNoLink() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        long linkedBefore = linkedEvents(teen);

        join(teen, parent)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.parent.id").value(id(parent).toString()))
                .andExpect(jsonPath("$.parent.displayName").value("Aigul"));

        assertThat(directory.areLinked(id(parent), id(teen))).isFalse();
        assertThat(linkedEvents(teen)).isEqualTo(linkedBefore);
        assertThat(events.stream(JoinRequested.class).filter(e -> e.teenId().equals(id(teen))))
                .singleElement()
                .satisfies(e -> assertThat(e.parentId()).isEqualTo(id(parent)));
    }

    @Test
    void askingAgainWhileOnePendingReturnsTheSameRequestWithoutAnotherEvent() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");

        String first = requestId(join(teen, parent).andExpect(status().isAccepted()));
        String second = requestId(join(teen, parent).andExpect(status().isAccepted()));

        assertThat(second).isEqualTo(first);
        assertThat(requestCount(parent)).isEqualTo(1);
        assertThat(events.stream(JoinRequested.class).filter(e -> e.teenId().equals(id(teen))))
                .hasSize(1);
    }

    @Test
    void anAlreadyLinkedTeenKeepsTheNoOpEvenWhenApprovalIsTurnedOnLater() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        join(teen, parent).andExpect(status().isOk());
        setApproval(parent, true);

        join(teen, parent).andExpect(status().isOk());

        assertThat(directory.areLinked(id(parent), id(teen))).isTrue();
        assertThat(requestCount(parent)).isZero();
        assertThat(linkedEvents(teen)).isEqualTo(1);
    }

    @Test
    void anInvalidCodeIsStillTheGenericError() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");

        mvc.perform(post("/links/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", bearer(teen))
                        .content("{\"code\":\"ZZZZZZ\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_join_code"));
    }

    @Test
    void aParentCannotAskAnotherParentToJoin() throws Exception {
        JsonNode owner = approvingParent("Aigul");
        JsonNode other = signIn("PARENT", "Bolat");

        join(other, owner)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        assertThat(requestCount(owner)).isZero();
    }

    @Test
    void theRateLimiterCountsPendingRequestsAndLocksOutWrongGuesses() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode guesser = signIn("TEEN", "Mallory");

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/links/join")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", bearer(guesser))
                            .content("{\"code\":\"ZZZZZZ\"}"))
                    .andExpect(status().isUnprocessableEntity());
        }

        join(guesser, parent)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("join_rate_limited"));
        assertThat(requestCount(parent)).isZero();
    }

    @Test
    void aPendingRequestResetsTheFailedAttemptCounter() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");

        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                mvc.perform(post("/links/join")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", bearer(teen))
                                .content("{\"code\":\"ZZZZZZ\"}"))
                        .andExpect(status().isUnprocessableEntity());
            }
            join(teen, parent).andExpect(status().isAccepted());
        }
    }

    @Test
    void theParentListsPendingRequestsNewestFirstWithTheTeenName() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode dana = signIn("TEEN", "Dana");
        JsonNode timur = signIn("TEEN", "Timur");
        String first = requestId(join(dana, parent));
        String second = requestId(join(timur, parent));

        mvc.perform(get("/links/requests").header("Authorization", bearer(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[0].teenId").value(id(timur).toString()))
                .andExpect(jsonPath("$.items[0].teenDisplayName").value("Timur"))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.items[1].id").value(first))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void theListShowsOnlyPendingRequestsOfTheCaller() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode other = approvingParent("Bolat");
        JsonNode dana = signIn("TEEN", "Dana");
        JsonNode timur = signIn("TEEN", "Timur");
        JsonNode aida = signIn("TEEN", "Aida");
        String declined = requestId(join(dana, parent));
        String approved = requestId(join(timur, parent));
        join(aida, other).andExpect(status().isAccepted());
        decide(parent, declined, "decline").andExpect(status().isOk());
        decide(parent, approved, "approve").andExpect(status().isOk());

        mvc.perform(get("/links/requests").header("Authorization", bearer(parent)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/links/requests").header("Authorization", bearer(other)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].teenId").value(id(aida).toString()));
    }

    @Test
    void aTeenCannotListRequests() throws Exception {
        JsonNode teen = signIn("TEEN", "Dana");

        mvc.perform(get("/links/requests").header("Authorization", bearer(teen)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"));
        mvc.perform(get("/links/requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void approvingLinksTheTeenOncePublishesFamilyLinkedAndReplayChangesNothing() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String request = requestId(join(teen, parent));

        decide(parent, request, "approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(request))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.teen.id").value(id(teen).toString()))
                .andExpect(jsonPath("$.teen.displayName").value("Dana"));
        decide(parent, request, "approve")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(directory.areLinked(id(parent), id(teen))).isTrue();
        assertThat(events.stream(FamilyLinked.class).filter(e -> e.teenId().equals(id(teen))))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.parentId()).isEqualTo(id(parent));
                    assertThat(e.generation()).isEqualTo(1);
                });
        assertThat(linkRows(parent, teen)).isEqualTo(1);
    }

    @Test
    void replayingAnApprovalAfterTheParentUnlinkedDoesNotRelink() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String request = requestId(join(teen, parent));
        decide(parent, request, "approve").andExpect(status().isOk());
        mvc.perform(delete("/links/" + id(teen)).header("Authorization", bearer(parent)))
                .andExpect(status().isNoContent());

        decide(parent, request, "approve").andExpect(status().isOk());

        assertThat(directory.areLinked(id(parent), id(teen))).isFalse();
        assertThat(linkedEvents(teen)).isEqualTo(1);
    }

    @Test
    void anApprovedRequestMakesTheTeenAppearInTheFamilyAndAfterUnlinkAskingAgainCreatesANewRequest() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String first = requestId(join(teen, parent));
        decide(parent, first, "approve").andExpect(status().isOk());
        mvc.perform(get("/family").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.members.length()").value(1));
        mvc.perform(delete("/links/" + id(teen)).header("Authorization", bearer(parent)))
                .andExpect(status().isNoContent());

        String second = requestId(join(teen, parent).andExpect(status().isAccepted()));
        decide(parent, second, "approve").andExpect(status().isOk());

        assertThat(second).isNotEqualTo(first);
        assertThat(directory.areLinked(id(parent), id(teen))).isTrue();
        assertThat(events.stream(FamilyLinked.class)
                        .filter(e -> e.teenId().equals(id(teen)))
                        .map(FamilyLinked::generation))
                .containsExactly(1, 2);
    }

    @Test
    void decliningClosesTheRequestWithoutALinkAndReplayIsHarmless() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String request = requestId(join(teen, parent));

        decide(parent, request, "decline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.teen.id").value(id(teen).toString()));
        decide(parent, request, "decline").andExpect(status().isOk());

        assertThat(directory.areLinked(id(parent), id(teen))).isFalse();
        assertThat(linkedEvents(teen)).isZero();
    }

    @Test
    void aDeclinedTeenCanAskAgainAndGetsAFreshPendingRequest() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String first = requestId(join(teen, parent));
        decide(parent, first, "decline").andExpect(status().isOk());

        String second = requestId(join(teen, parent).andExpect(status().isAccepted()));

        assertThat(second).isNotEqualTo(first);
        assertThat(requestCount(parent)).isEqualTo(2);
    }

    @Test
    void aDecidedRequestCannotBeDecidedTheOtherWay() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String approved = requestId(join(teen, parent));
        decide(parent, approved, "approve").andExpect(status().isOk());
        decide(parent, approved, "decline")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
        JsonNode second = signIn("TEEN", "Timur");
        String declined = requestId(join(second, parent));
        decide(parent, declined, "decline").andExpect(status().isOk());

        decide(parent, declined, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));

        assertThat(directory.areLinked(id(parent), id(second))).isFalse();
    }

    @Test
    void onlyTheRequestedParentCanDecide() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode other = signIn("PARENT", "Bolat");
        JsonNode teen = signIn("TEEN", "Dana");
        String request = requestId(join(teen, parent));

        for (String action : new String[] {"approve", "decline"}) {
            decide(other, request, action)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
            decide(teen, request, action)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("forbidden"));
        }

        assertThat(directory.areLinked(id(parent), id(teen))).isFalse();
        mvc.perform(get("/links/requests").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void decidingAnUnknownOrMalformedRequestIsNotFoundOrBadRequest() throws Exception {
        JsonNode parent = signIn("PARENT", "Aigul");

        decide(parent, UUID.randomUUID().toString(), "approve")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        decide(parent, "not-a-uuid", "decline")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void theTeenWithdrawsAPendingRequestAndItLeavesTheParentsList() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String request = requestId(join(teen, parent));

        withdraw(teen, request).andExpect(status().isNoContent());
        withdraw(teen, request).andExpect(status().isNoContent());

        mvc.perform(get("/links/requests").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.items.length()").value(0));
        decide(parent, request, "approve")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
        assertThat(directory.areLinked(id(parent), id(teen))).isFalse();
        String again = requestId(join(teen, parent).andExpect(status().isAccepted()));
        assertThat(again).isNotEqualTo(request);
    }

    @Test
    void aDecidedRequestCannotBeWithdrawn() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String request = requestId(join(teen, parent));
        decide(parent, request, "decline").andExpect(status().isOk());

        withdraw(teen, request)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("invalid_transition"));
    }

    @Test
    void onlyTheRequestingTeenCanWithdrawAndUnknownIsNotFound() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        JsonNode other = signIn("TEEN", "Timur");
        String request = requestId(join(teen, parent));

        withdraw(other, request).andExpect(status().isForbidden());
        withdraw(parent, request).andExpect(status().isForbidden());
        withdraw(teen, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(get("/links/requests").header("Authorization", bearer(parent)))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void turningApprovalOffLeavesPendingRequestsDecidableAndLetsNewJoinsLinkDirectly() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode waiting = signIn("TEEN", "Dana");
        JsonNode newcomer = signIn("TEEN", "Timur");
        String request = requestId(join(waiting, parent));
        setApproval(parent, false).andExpect(status().isOk());

        join(newcomer, parent).andExpect(status().isOk());
        decide(parent, request, "approve").andExpect(status().isOk());

        assertThat(directory.areLinked(id(parent), id(newcomer))).isTrue();
        assertThat(directory.areLinked(id(parent), id(waiting))).isTrue();
    }

    @Test
    void anApprovedTeenWhoAlreadyJoinedDirectlyGetsNoSecondLinkEvent() throws Exception {
        JsonNode parent = approvingParent("Aigul");
        JsonNode teen = signIn("TEEN", "Dana");
        String request = requestId(join(teen, parent));
        setApproval(parent, false).andExpect(status().isOk());
        join(teen, parent).andExpect(status().isOk());

        decide(parent, request, "approve").andExpect(status().isOk());

        assertThat(linkedEvents(teen)).isEqualTo(1);
        assertThat(linkRows(parent, teen)).isEqualTo(1);
    }

    private JsonNode approvingParent(String name) throws Exception {
        JsonNode parent = signIn("PARENT", name);
        setApproval(parent, true).andExpect(status().isOk());
        return parent;
    }

    private ResultActions setApproval(JsonNode who, boolean required) throws Exception {
        return mvc.perform(put("/me/join-approval")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(who))
                .content("{\"required\":" + required + "}"));
    }

    private ResultActions join(JsonNode teen, JsonNode parent) throws Exception {
        return mvc.perform(post("/links/join")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", bearer(teen))
                .content("{\"code\":\"" + parent.at("/user/joinCode").asText() + "\"}"));
    }

    private ResultActions decide(JsonNode who, String requestId, String action) throws Exception {
        return mvc.perform(post("/links/requests/" + requestId + "/" + action).header("Authorization", bearer(who)));
    }

    private ResultActions withdraw(JsonNode who, String requestId) throws Exception {
        return mvc.perform(delete("/links/requests/" + requestId).header("Authorization", bearer(who)));
    }

    private String requestId(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString())
                .get("requestId")
                .asText();
    }

    private long linkedEvents(JsonNode teen) {
        return events.stream(FamilyLinked.class)
                .filter(e -> e.teenId().equals(id(teen)))
                .count();
    }

    private int requestCount(JsonNode parent) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.join_requests WHERE parent_id = ?", Integer.class, id(parent));
    }

    private int linkRows(JsonNode parent, JsonNode teen) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM identity.family_links WHERE parent_id = ? AND teen_id = ?",
                Integer.class,
                id(parent),
                id(teen));
    }

    private JsonNode signIn(String role, String name) throws Exception {
        String body = json.writeValueAsString(json.createObjectNode()
                .put("idToken", tokenFor("sub-" + UUID.randomUUID(), name))
                .put("role", role)
                .put("displayName", name));
        String response = mvc.perform(post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return json.readTree(response);
    }

    private static UUID id(JsonNode signInResponse) {
        return UUID.fromString(signInResponse.at("/user/id").asText());
    }

    private static String bearer(JsonNode signInResponse) {
        return "Bearer " + signInResponse.get("token").asText();
    }
}

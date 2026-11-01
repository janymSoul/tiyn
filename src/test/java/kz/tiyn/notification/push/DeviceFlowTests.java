package kz.tiyn.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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
class DeviceFlowTests {

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

    private ResultActions register(Person who, String token, String platform, String locale) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("token", token, "platform", platform, "locale", locale));
        return mvc.perform(put("/devices")
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int rows(String token) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification.device_tokens WHERE token = ?", Integer.class, token);
    }

    private UUID ownerOf(String token) {
        return jdbc.queryForObject("SELECT user_id FROM notification.device_tokens WHERE token = ?", UUID.class, token);
    }

    @Test
    void aDeviceIsRegisteredOnceAndReRegisteringUpdatesIt() throws Exception {
        Family f = fx.linkedFamily();
        String token = "tok-" + UUID.randomUUID();

        register(f.teen(), token, "android", "ru").andExpect(status().isNoContent());
        register(f.teen(), token, "IOS", "kk").andExpect(status().isNoContent());

        assertThat(rows(token)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT platform || '/' || locale FROM notification.device_tokens WHERE token = ?",
                        String.class,
                        token))
                .isEqualTo("IOS/kk");
    }

    @Test
    void aTokenThatMovesToAnotherAccountIsReassigned() throws Exception {
        Family f = fx.linkedFamily();
        String token = "tok-" + UUID.randomUUID();
        register(f.teen(), token, "ANDROID", "ru").andExpect(status().isNoContent());

        register(f.parent(), token, "ANDROID", "ru").andExpect(status().isNoContent());

        assertThat(rows(token)).isEqualTo(1);
        assertThat(ownerOf(token)).isEqualTo(f.parent().id());
    }

    @Test
    void invalidInputIsRefused() throws Exception {
        Family f = fx.linkedFamily();

        register(f.teen(), " ", "ANDROID", "ru")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_token"));
        register(f.teen(), "x".repeat(4097), "ANDROID", "ru")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_token"));
        register(f.teen(), "t1", "WINDOWS", "ru")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_platform"));
        register(f.teen(), "t2", "ANDROID", "en")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("invalid_locale"));
    }

    @Test
    void unregisteringRemovesOnlyTheCallersOwnToken() throws Exception {
        Family f = fx.linkedFamily();
        String token = "tok-" + UUID.randomUUID();
        register(f.teen(), token, "ANDROID", "ru").andExpect(status().isNoContent());

        mvc.perform(delete("/devices")
                        .param("token", token)
                        .header("Authorization", f.parent().bearer()))
                .andExpect(status().isNoContent());
        assertThat(rows(token)).isEqualTo(1);

        mvc.perform(delete("/devices")
                        .param("token", token)
                        .header("Authorization", f.teen().bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/devices")
                        .param("token", token)
                        .header("Authorization", f.teen().bearer()))
                .andExpect(status().isNoContent());
        assertThat(rows(token)).isZero();
    }

    @Test
    void registrationNeedsASignedInUser() throws Exception {
        mvc.perform(put("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"platform\":\"ANDROID\",\"locale\":\"ru\"}"))
                .andExpect(status().isUnauthorized());
    }
}

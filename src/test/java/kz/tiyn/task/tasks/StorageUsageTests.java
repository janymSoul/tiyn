package kz.tiyn.task.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kz.tiyn.media.Storage;
import kz.tiyn.support.IntegrationTest;
import kz.tiyn.task.TaskFixtures;
import kz.tiyn.task.TaskFixtures.Family;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
@AutoConfigureMockMvc
@Import(StorageUsageTests.CountingConfig.class)
class StorageUsageTests {

    static class CountingStorage implements Storage {

        final AtomicInteger puts = new AtomicInteger();

        @Override
        public String put(String scope, String contentType, InputStream body) {
            puts.incrementAndGet();
            return "counted/" + scope + "/" + UUID.randomUUID();
        }

        @Override
        public URI presignView(String objectKey) {
            return URI.create("memory://" + objectKey);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CountingConfig {

        @Bean
        @Primary
        CountingStorage countingStorage() {
            return new CountingStorage();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    CountingStorage storage;

    TaskFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new TaskFixtures(mvc, json);
    }

    private ResultActions submitImage(Family f, UUID id, String key) throws Exception {
        return mvc.perform(multipart("/tasks/" + id + "/submit")
                .file(new MockMultipartFile("file", "p.png", "image/png", new byte[] {1, 2, 3}))
                .header("Authorization", f.teen().bearer())
                .header("Idempotency-Key", key));
    }

    @Test
    void anOpenTaskStoresTheImageOnce() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        int before = storage.puts.get();

        submitImage(f, id, "s1").andExpect(status().isOk());

        assertThat(storage.puts.get() - before).isEqualTo(1);
    }

    @Test
    void aReplayOrADecidedTaskNeverTouchesStorage() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        submitImage(f, id, "s2").andExpect(status().isOk());
        int afterFirst = storage.puts.get();

        submitImage(f, id, "s2").andExpect(status().isOk());
        submitImage(f, id, "another-key").andExpect(status().isConflict());
        fx.send("/tasks/" + id + "/approve", "", f.parent().bearer());
        submitImage(f, id, "after-approval").andExpect(status().isConflict());

        assertThat(storage.puts.get()).isEqualTo(afterFirst);
    }

    @Test
    void aCancelledTaskNeverTouchesStorage() throws Exception {
        Family f = fx.linkedFamily();
        UUID id = fx.createTask(f, 100);
        fx.send("/tasks/" + id + "/cancel", "", f.parent().bearer());
        int before = storage.puts.get();

        submitImage(f, id, "s3").andExpect(status().isConflict());

        assertThat(storage.puts.get()).isEqualTo(before);
    }
}

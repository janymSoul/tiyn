package kz.tiyn.task.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import kz.tiyn.platform.jooq.task.tables.records.TaskProofsRecord;
import kz.tiyn.task.tasks.dto.ProofExport;
import org.junit.jupiter.api.Test;

class ProofExportTests {

    private static final OffsetDateTime SUBMITTED = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    private static TaskProofsRecord proof(String objectKey, String text) {
        var record = new TaskProofsRecord();
        record.setId(UUID.randomUUID());
        record.setTaskId(UUID.randomUUID());
        record.setAttemptNo(2);
        record.setObjectKey(objectKey);
        record.setTextBody(text);
        record.setCreatedAt(SUBMITTED);
        return record;
    }

    @Test
    void anImageProofCarriesAViewUrlAndNeverTheObjectKey() {
        ProofExport export = ProofExport.from(proof("proofs/t/abc", null), key -> URI.create("https://view/" + key));

        assertThat(export.hasImage()).isTrue();
        assertThat(export.imageUrl()).isEqualTo("https://view/proofs/t/abc");
        assertThat(export.text()).isNull();
        assertThat(export.attemptNo()).isEqualTo(2);
        assertThat(export.submittedAt()).isEqualTo(SUBMITTED.toInstant());
    }

    @Test
    void aTextProofNeverAsksForAViewUrl() {
        ProofExport export = ProofExport.from(proof(null, "washed"), key -> {
            throw new AssertionError("no image, no url");
        });

        assertThat(export.hasImage()).isFalse();
        assertThat(export.imageUrl()).isNull();
        assertThat(export.text()).isEqualTo("washed");
    }
}

package kz.tiyn.task.tasks.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.time.Instant;
import java.util.function.Function;
import kz.tiyn.platform.jooq.task.tables.records.TaskProofsRecord;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProofExport(int attemptNo, String text, boolean hasImage, String imageUrl, Instant submittedAt) {

    public static ProofExport from(TaskProofsRecord proof, Function<String, URI> viewUrl) {
        boolean hasImage = proof.getObjectKey() != null;
        return new ProofExport(
                proof.getAttemptNo(),
                proof.getTextBody(),
                hasImage,
                hasImage ? viewUrl.apply(proof.getObjectKey()).toString() : null,
                proof.getCreatedAt().toInstant());
    }
}

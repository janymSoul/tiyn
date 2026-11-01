package kz.tiyn.task.tasks;

import java.time.Instant;
import java.util.UUID;
import kz.tiyn.task.tasks.error.TaskError;
import kz.tiyn.task.tasks.error.TaskException;

record Proof(UUID id, int attemptNo, String objectKey, String textBody, Instant createdAt) {

    static final int MAX_TEXT = 2000;

    static Proof submitted(UUID id, int attemptNo, String objectKey, String textBody, Instant now) {
        boolean hasImage = objectKey != null;
        boolean hasText = textBody != null && !textBody.isBlank() && textBody.length() <= MAX_TEXT;
        if (hasImage == hasText) {
            throw new TaskException(TaskError.UNSUPPORTED_PROOF);
        }
        return new Proof(id, attemptNo, objectKey, hasText ? textBody : null, now);
    }
}

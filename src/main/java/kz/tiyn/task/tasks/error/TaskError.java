package kz.tiyn.task.tasks.error;

public enum TaskError {
    NOT_FOUND,
    FORBIDDEN,
    INVALID_TRANSITION,
    CONCURRENT_MODIFICATION,
    DEADLINE_PASSED,
    INVALID_REWARD,
    INVALID_DEADLINE,
    INVALID_TITLE,
    INVALID_NOTE,
    INVALID_MILESTONE,
    UNSUPPORTED_PROOF,
    INVALID_IDEMPOTENCY_KEY,
    TEEN_REQUIRED,
    INVALID_FILTER;

    public String code() {
        return name().toLowerCase();
    }
}

package kz.tiyn.task;

import java.util.UUID;

public record TaskRejected(UUID taskId, UUID teenId, String note) {}

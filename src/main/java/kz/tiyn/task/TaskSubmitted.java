package kz.tiyn.task;

import java.util.UUID;

public record TaskSubmitted(UUID taskId, UUID parentId, UUID teenId, boolean withImage) {}

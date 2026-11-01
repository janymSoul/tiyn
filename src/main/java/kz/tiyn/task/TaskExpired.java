package kz.tiyn.task;

import java.util.UUID;

public record TaskExpired(UUID taskId, UUID teenId) {}

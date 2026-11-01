package kz.tiyn.task;

import java.util.UUID;

public record TaskCancelled(UUID taskId, UUID teenId) {}

package kz.tiyn.task;

import java.util.UUID;

public record TaskRedoRequested(UUID taskId, UUID teenId, String note) {}

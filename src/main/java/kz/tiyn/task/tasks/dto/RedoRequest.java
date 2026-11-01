package kz.tiyn.task.tasks.dto;

import java.time.Instant;

public record RedoRequest(String note, Instant deadlineAt) {}

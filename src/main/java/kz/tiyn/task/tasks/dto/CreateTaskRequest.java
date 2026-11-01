package kz.tiyn.task.tasks.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateTaskRequest(
        UUID teenId,
        String kind,
        String title,
        String description,
        long rewardAmount,
        String currency,
        Instant deadlineAt) {}

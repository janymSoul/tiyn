package kz.tiyn.task.tasks.dto;

import java.time.Instant;
import java.util.UUID;

public record CreateTaskRequest(
        UUID teenId,
        String kind,
        String title,
        String description,
        long rewardAmount,
        long penaltyAmount,
        String currency,
        Instant deadlineAt,
        UUID milestoneId) {}

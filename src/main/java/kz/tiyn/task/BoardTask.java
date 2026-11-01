package kz.tiyn.task;

import java.time.Instant;
import java.util.UUID;

public record BoardTask(
        UUID id,
        String kind,
        String title,
        String description,
        long rewardAmount,
        long penaltyAmount,
        String currency,
        String state,
        Instant deadlineAt,
        UUID milestoneId,
        Integer approvedTasks,
        Integer totalTasks) {}

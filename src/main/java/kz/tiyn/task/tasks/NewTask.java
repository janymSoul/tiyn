package kz.tiyn.task.tasks;

import java.time.Instant;
import java.util.UUID;

record NewTask(
        UUID parentId,
        UUID teenId,
        TaskKind kind,
        String title,
        String description,
        long rewardAmount,
        long penaltyAmount,
        String currency,
        Instant deadlineAt) {}

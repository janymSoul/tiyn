package kz.tiyn.task.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record TaskSnapshot(
        UUID id,
        UUID seriesId,
        LocalDate occurrenceDate,
        UUID milestoneId,
        UUID parentId,
        UUID teenId,
        TaskKind kind,
        String title,
        String description,
        long rewardAmount,
        long penaltyAmount,
        String currency,
        TaskState state,
        Instant deadlineAt,
        int version,
        Instant submittedAt,
        Instant decidedAt,
        UUID decidedBy,
        String decisionNote) {}

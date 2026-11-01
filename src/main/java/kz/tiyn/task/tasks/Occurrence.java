package kz.tiyn.task.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Occurrence(
        UUID seriesId,
        LocalDate date,
        UUID parentId,
        UUID teenId,
        String title,
        String description,
        long rewardAmount,
        long penaltyAmount,
        String currency,
        Instant deadlineAt) {}

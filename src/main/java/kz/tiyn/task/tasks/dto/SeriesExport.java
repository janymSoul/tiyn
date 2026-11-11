package kz.tiyn.task.tasks.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeriesExport(
        UUID id,
        UUID teenId,
        String kind,
        String title,
        String description,
        long rewardAmount,
        long penaltyAmount,
        String currency,
        String recurrence,
        String timezone,
        LocalTime dueTime,
        LocalDate startsOn,
        boolean active,
        Instant createdAt) {}

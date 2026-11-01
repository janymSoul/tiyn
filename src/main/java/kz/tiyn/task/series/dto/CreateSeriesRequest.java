package kz.tiyn.task.series.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record CreateSeriesRequest(
        UUID teenId,
        String title,
        String description,
        long rewardAmount,
        String currency,
        String recurrence,
        String timezone,
        LocalTime dueTime,
        LocalDate startsOn) {}

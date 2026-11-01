package kz.tiyn.task.series;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

record NewSeries(
        UUID teenId,
        String title,
        String description,
        long rewardAmount,
        String currency,
        String recurrence,
        String timezone,
        LocalTime dueTime,
        LocalDate startsOn) {}

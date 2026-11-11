package kz.tiyn.task.series.dto;

import java.time.LocalTime;

public record UpdateSeriesRequest(
        String title,
        String description,
        Long rewardAmount,
        Long penaltyAmount,
        LocalTime dueTime,
        String timezone,
        Boolean active) {}

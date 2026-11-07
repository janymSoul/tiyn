package kz.tiyn.task.tasks.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskResponse(
        UUID id,
        UUID teenId,
        UUID parentId,
        String kind,
        String title,
        String description,
        long rewardAmount,
        long penaltyAmount,
        String currency,
        String state,
        Instant deadlineAt,
        Instant submittedAt,
        Instant decidedAt,
        String decisionNote,
        UUID milestoneId,
        UUID seriesId,
        Integer approvedTasks,
        Integer totalTasks) {}

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
        String currency,
        String state,
        Instant deadlineAt,
        Instant submittedAt,
        Instant decidedAt,
        String decisionNote,
        UUID milestoneId,
        Integer approvedTasks,
        Integer totalTasks) {}

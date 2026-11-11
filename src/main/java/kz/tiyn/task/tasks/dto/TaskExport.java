package kz.tiyn.task.tasks.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskExport(
        UUID id,
        UUID parentId,
        UUID teenId,
        UUID milestoneId,
        UUID seriesId,
        LocalDate occurrenceDate,
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
        UUID decidedBy,
        String decisionNote,
        Instant createdAt,
        List<ProofExport> proofs) {}

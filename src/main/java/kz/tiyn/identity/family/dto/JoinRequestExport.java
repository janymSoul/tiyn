package kz.tiyn.identity.family.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JoinRequestExport(
        UUID id,
        UUID parentId,
        String parentDisplayName,
        UUID teenId,
        String teenDisplayName,
        String state,
        Instant createdAt,
        Instant decidedAt) {}

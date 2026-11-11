package kz.tiyn.identity.family.dto;

import java.time.Instant;
import java.util.UUID;

public record PendingJoinResponse(UUID id, UUID teenId, String teenDisplayName, Instant createdAt) {}

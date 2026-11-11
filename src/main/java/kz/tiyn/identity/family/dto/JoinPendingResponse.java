package kz.tiyn.identity.family.dto;

import java.util.UUID;

public record JoinPendingResponse(UUID requestId, String status, JoinResponse.Parent parent) {}

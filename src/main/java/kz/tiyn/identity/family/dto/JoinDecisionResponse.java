package kz.tiyn.identity.family.dto;

import java.util.UUID;

public record JoinDecisionResponse(UUID requestId, String status, Teen teen) {

    public record Teen(UUID id, String displayName) {}
}

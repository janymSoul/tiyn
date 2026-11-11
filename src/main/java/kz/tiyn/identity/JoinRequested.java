package kz.tiyn.identity;

import java.util.UUID;

public record JoinRequested(UUID requestId, UUID parentId, UUID teenId) {}

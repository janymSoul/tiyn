package kz.tiyn.identity.family;

import java.time.Instant;
import java.util.UUID;

record PendingJoinRequest(UUID id, UUID teenId, String teenDisplayName, Instant createdAt) {}

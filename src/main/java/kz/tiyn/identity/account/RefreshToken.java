package kz.tiyn.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

final class RefreshToken {

    private static final int SECRET_BYTES = 32;

    private final UUID id;
    private final UUID userId;
    private final UUID familyId;
    private final String hash;
    private final Instant expiresAt;
    private final Instant usedAt;
    private final Instant revokedAt;

    private RefreshToken(
            UUID id, UUID userId, UUID familyId, String hash, Instant expiresAt, Instant usedAt, Instant revokedAt) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.hash = hash;
        this.expiresAt = expiresAt;
        this.usedAt = usedAt;
        this.revokedAt = revokedAt;
    }

    record Issued(String secret, RefreshToken token) {}

    static Issued issue(UUID userId, UUID familyId, Instant now, Duration ttl, SecureRandom random) {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken token =
                new RefreshToken(UUID.randomUUID(), userId, familyId, hashOf(secret), now.plus(ttl), null, null);
        return new Issued(secret, token);
    }

    static RefreshToken restore(
            UUID id, UUID userId, UUID familyId, String hash, Instant expiresAt, Instant usedAt, Instant revokedAt) {
        return new RefreshToken(id, userId, familyId, hash, expiresAt, usedAt, revokedAt);
    }

    static String hashOf(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    boolean isReplay() {
        return usedAt != null;
    }

    boolean isUsableAt(Instant now) {
        return usedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    UUID id() {
        return id;
    }

    UUID userId() {
        return userId;
    }

    UUID familyId() {
        return familyId;
    }

    String hash() {
        return hash;
    }

    Instant expiresAt() {
        return expiresAt;
    }
}

package kz.tiyn.identity.domain;

import java.time.Instant;
import java.util.UUID;

public final class FamilyLink {

    private final UUID parentId;
    private final UUID teenId;
    private int generation;
    private Instant removedAt;

    private FamilyLink(UUID parentId, UUID teenId, int generation, Instant removedAt) {
        this.parentId = parentId;
        this.teenId = teenId;
        this.generation = generation;
        this.removedAt = removedAt;
    }

    public static FamilyLink create(UUID parentId, UUID teenId) {
        if (parentId.equals(teenId)) {
            throw new IllegalArgumentException("a link cannot join a user to themselves");
        }
        return new FamilyLink(parentId, teenId, 1, null);
    }

    public static FamilyLink restore(UUID parentId, UUID teenId, int generation, Instant removedAt) {
        return new FamilyLink(parentId, teenId, generation, removedAt);
    }

    public boolean relink() {
        if (isActive()) {
            return false;
        }
        removedAt = null;
        generation++;
        return true;
    }

    public boolean remove(Instant now) {
        if (!isActive()) {
            return false;
        }
        removedAt = now;
        return true;
    }

    public boolean isActive() {
        return removedAt == null;
    }

    public UUID parentId() {
        return parentId;
    }

    public UUID teenId() {
        return teenId;
    }

    public int generation() {
        return generation;
    }

    public Instant removedAt() {
        return removedAt;
    }
}

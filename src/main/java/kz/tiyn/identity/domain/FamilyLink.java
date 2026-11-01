package kz.tiyn.identity.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kz.tiyn.identity.FamilyLinked;
import kz.tiyn.identity.FamilyUnlinked;
import kz.tiyn.platform.security.Role;

public final class FamilyLink {

    private final UUID parentId;
    private final UUID teenId;
    private int generation;
    private Instant removedAt;
    private final List<Object> events = new ArrayList<>();

    private FamilyLink(UUID parentId, UUID teenId, int generation, Instant removedAt) {
        this.parentId = parentId;
        this.teenId = teenId;
        this.generation = generation;
        this.removedAt = removedAt;
    }

    public static FamilyLink between(User parent, User teen) {
        if (parent.role() != Role.PARENT || teen.role() != Role.TEEN) {
            throw new IdentityException(IdentityError.FORBIDDEN);
        }
        return new FamilyLink(parent.id(), teen.id(), 0, null);
    }

    public static FamilyLink restore(UUID parentId, UUID teenId, int generation, Instant removedAt) {
        return new FamilyLink(parentId, teenId, generation, removedAt);
    }

    public void link() {
        if (isActive()) {
            return;
        }
        generation++;
        removedAt = null;
        events.add(new FamilyLinked(parentId, teenId, generation));
    }

    public void unlink(Instant now) {
        if (!isActive()) {
            return;
        }
        removedAt = now;
        events.add(new FamilyUnlinked(parentId, teenId, generation));
    }

    public List<Object> pullEvents() {
        List<Object> raised = List.copyOf(events);
        events.clear();
        return raised;
    }

    public boolean isActive() {
        return generation > 0 && removedAt == null;
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

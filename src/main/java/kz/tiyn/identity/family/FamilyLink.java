package kz.tiyn.identity.family;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kz.tiyn.identity.FamilyLinked;
import kz.tiyn.identity.FamilyUnlinked;
import kz.tiyn.identity.account.User;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.security.Role;

final class FamilyLink {

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

    static FamilyLink between(User parent, User teen) {
        if (parent.role() != Role.PARENT || teen.role() != Role.TEEN) {
            throw new IdentityException(IdentityError.FORBIDDEN);
        }
        return new FamilyLink(parent.id(), teen.id(), 0, null);
    }

    static FamilyLink restore(UUID parentId, UUID teenId, int generation, Instant removedAt) {
        return new FamilyLink(parentId, teenId, generation, removedAt);
    }

    void link() {
        if (isActive()) {
            return;
        }
        generation++;
        removedAt = null;
        events.add(new FamilyLinked(parentId, teenId, generation));
    }

    void unlink(Instant now) {
        if (!isActive()) {
            return;
        }
        removedAt = now;
        events.add(new FamilyUnlinked(parentId, teenId, generation));
    }

    List<Object> pullEvents() {
        List<Object> raised = List.copyOf(events);
        events.clear();
        return raised;
    }

    boolean isActive() {
        return generation > 0 && removedAt == null;
    }

    UUID parentId() {
        return parentId;
    }

    UUID teenId() {
        return teenId;
    }

    int generation() {
        return generation;
    }

    Instant removedAt() {
        return removedAt;
    }
}

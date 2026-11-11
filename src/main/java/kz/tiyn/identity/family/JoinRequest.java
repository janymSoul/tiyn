package kz.tiyn.identity.family;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kz.tiyn.identity.JoinRequested;
import kz.tiyn.identity.account.User;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.event.EventLog;
import kz.tiyn.platform.security.Role;

final class JoinRequest {

    private final UUID id;
    private final UUID parentId;
    private final UUID teenId;
    private JoinRequestState state;
    private Instant decidedAt;
    private final EventLog events = new EventLog();

    private JoinRequest(UUID id, UUID parentId, UUID teenId, JoinRequestState state, Instant decidedAt) {
        this.id = id;
        this.parentId = parentId;
        this.teenId = teenId;
        this.state = state;
        this.decidedAt = decidedAt;
    }

    static JoinRequest submit(UUID id, User parent, User teen) {
        if (parent.role() != Role.PARENT || teen.role() != Role.TEEN) {
            throw new IdentityException(IdentityError.FORBIDDEN);
        }
        JoinRequest request = new JoinRequest(id, parent.id(), teen.id(), JoinRequestState.PENDING, null);
        request.events.record(new JoinRequested(id, parent.id(), teen.id()));
        return request;
    }

    static JoinRequest restore(UUID id, UUID parentId, UUID teenId, JoinRequestState state, Instant decidedAt) {
        return new JoinRequest(id, parentId, teenId, state, decidedAt);
    }

    void approveBy(UUID actorId, Instant now) {
        requireOwner(parentId, actorId);
        settle(JoinRequestState.APPROVED, now);
    }

    void declineBy(UUID actorId, Instant now) {
        requireOwner(parentId, actorId);
        settle(JoinRequestState.DECLINED, now);
    }

    void withdrawBy(UUID actorId, Instant now) {
        requireOwner(teenId, actorId);
        settle(JoinRequestState.WITHDRAWN, now);
    }

    private void settle(JoinRequestState target, Instant now) {
        if (state == target) {
            return;
        }
        if (state != JoinRequestState.PENDING) {
            throw new IdentityException(IdentityError.INVALID_TRANSITION);
        }
        state = target;
        decidedAt = now;
    }

    private static void requireOwner(UUID owner, UUID actorId) {
        if (!owner.equals(actorId)) {
            throw new IdentityException(IdentityError.FORBIDDEN);
        }
    }

    List<Object> pullEvents() {
        return events.drain();
    }

    boolean isPending() {
        return state == JoinRequestState.PENDING;
    }

    UUID id() {
        return id;
    }

    UUID parentId() {
        return parentId;
    }

    UUID teenId() {
        return teenId;
    }

    JoinRequestState state() {
        return state;
    }

    Instant decidedAt() {
        return decidedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JoinRequest request && id.equals(request.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

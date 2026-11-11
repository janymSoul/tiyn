package kz.tiyn.identity.account;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kz.tiyn.identity.AccountDeleted;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.event.EventLog;
import kz.tiyn.platform.security.Role;

public final class User {

    static final int MAX_DISPLAY_NAME = 100;
    static final String DELETED_DISPLAY_NAME = "Deleted user";
    static final String TOMBSTONE_PREFIX = "deleted:";

    private final UUID id;
    private final Role role;
    private final EventLog events = new EventLog();
    private String idpSub;
    private String joinCode;
    private String displayName;
    private Instant deletedAt;

    private User(UUID id, String idpSub, Role role, String joinCode, String displayName) {
        this.id = id;
        this.idpSub = idpSub;
        this.role = role;
        this.joinCode = joinCode;
        this.displayName = displayName;
    }

    static User register(UUID id, String idpSub, Role role, String displayName, JoinCode joinCode) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > MAX_DISPLAY_NAME) {
            throw new IdentityException(IdentityError.INVALID_DISPLAY_NAME);
        }
        String code = role == Role.PARENT ? joinCode.value() : null;
        return new User(id, idpSub, role, code, name);
    }

    public static User restore(UUID id, String idpSub, Role role, String joinCode, String displayName) {
        return new User(id, idpSub, role, joinCode, displayName);
    }

    void delete(Instant now) {
        if (deletedAt != null) {
            return;
        }
        idpSub = TOMBSTONE_PREFIX + id;
        joinCode = null;
        displayName = DELETED_DISPLAY_NAME;
        deletedAt = now;
        events.record(new AccountDeleted(id, role));
    }

    List<Object> pullEvents() {
        return events.drain();
    }

    boolean isDeleted() {
        return deletedAt != null;
    }

    Instant deletedAt() {
        return deletedAt;
    }

    public UUID id() {
        return id;
    }

    String idpSub() {
        return idpSub;
    }

    public Role role() {
        return role;
    }

    public String joinCode() {
        return joinCode;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof User user && id.equals(user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

package kz.tiyn.identity.account;

import java.util.Objects;
import java.util.UUID;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.platform.security.Role;

public final class User {

    private final UUID id;
    private final String idpSub;
    private final Role role;
    private final String joinCode;
    private String displayName;

    private User(UUID id, String idpSub, Role role, String joinCode, String displayName) {
        this.id = id;
        this.idpSub = idpSub;
        this.role = role;
        this.joinCode = joinCode;
        this.displayName = displayName;
    }

    static User register(UUID id, String idpSub, Role role, String displayName, JoinCode joinCode) {
        String name = DisplayName.parse(displayName).value();
        String code = role == Role.PARENT ? joinCode.value() : null;
        return new User(id, idpSub, role, code, name);
    }

    User withJoinCode(JoinCode newCode) {
        if (role != Role.PARENT) {
            throw new IdentityException(IdentityError.FORBIDDEN);
        }
        return new User(id, idpSub, role, newCode.value(), displayName);
    }

    public static User restore(UUID id, String idpSub, Role role, String joinCode, String displayName) {
        return new User(id, idpSub, role, joinCode, displayName);
    }

    void rename(String newDisplayName) {
        this.displayName = DisplayName.parse(newDisplayName).value();
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

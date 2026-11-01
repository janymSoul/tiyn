package kz.tiyn.identity.account;

import java.util.UUID;
import kz.tiyn.platform.security.Role;

public record User(UUID id, String idpSub, Role role, String joinCode, String displayName) {

    static final int MAX_DISPLAY_NAME = 100;

    static User register(UUID id, String idpSub, Role role, String displayName, JoinCode joinCode) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > MAX_DISPLAY_NAME) {
            throw new IdentityException(IdentityError.INVALID_DISPLAY_NAME);
        }
        String code = role == Role.PARENT ? joinCode.value() : null;
        return new User(id, idpSub, role, code, name);
    }
}

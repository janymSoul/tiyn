package kz.tiyn.identity.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
record UserResponse(UUID id, String role, String displayName, String joinCode) {

    static UserResponse from(User user) {
        return new UserResponse(user.id(), user.role().name(), user.displayName(), user.joinCode());
    }
}

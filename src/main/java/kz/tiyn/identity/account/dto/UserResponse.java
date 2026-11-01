package kz.tiyn.identity.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import kz.tiyn.identity.account.User;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(UUID id, String role, String displayName, String joinCode) {

    public static UserResponse from(User user) {
        return new UserResponse(user.id(), user.role().name(), user.displayName(), user.joinCode());
    }
}

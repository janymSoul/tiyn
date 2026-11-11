package kz.tiyn.identity.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import kz.tiyn.identity.account.User;
import kz.tiyn.platform.security.Role;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(UUID id, String role, String displayName, String joinCode, Boolean joinApproval) {

    public static UserResponse from(User user) {
        Boolean joinApproval = user.role() == Role.PARENT ? user.joinApprovalRequired() : null;
        return new UserResponse(user.id(), user.role().name(), user.displayName(), user.joinCode(), joinApproval);
    }
}

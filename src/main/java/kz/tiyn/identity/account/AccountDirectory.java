package kz.tiyn.identity.account;

import java.util.Optional;
import java.util.UUID;

public interface AccountDirectory {

    User requireUser(UUID userId);

    User requireParentByJoinCode(String rawCode);

    Optional<User> lockActiveUser(UUID userId);

    Optional<User> shareActiveUser(UUID userId);

    void delete(User user);
}

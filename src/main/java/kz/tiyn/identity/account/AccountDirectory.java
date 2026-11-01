package kz.tiyn.identity.account;

import java.util.UUID;

public interface AccountDirectory {

    User requireUser(UUID userId);

    User requireParentByJoinCode(String rawCode);
}

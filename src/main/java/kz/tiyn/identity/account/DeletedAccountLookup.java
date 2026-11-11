package kz.tiyn.identity.account;

import java.util.UUID;
import kz.tiyn.platform.security.DeletedAccounts;
import org.springframework.stereotype.Component;

@Component
class DeletedAccountLookup implements DeletedAccounts {

    private final UserStore users;

    DeletedAccountLookup(UserStore users) {
        this.users = users;
    }

    @Override
    public boolean isDeleted(UUID userId) {
        return users.isDeleted(userId);
    }
}

package kz.tiyn.identity;

import java.util.UUID;
import kz.tiyn.platform.security.Role;

public interface OpenPayoutCheck {

    boolean hasOpenPayout(UUID userId, Role role);
}

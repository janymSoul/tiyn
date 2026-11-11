package kz.tiyn.platform.security;

import java.util.UUID;

public interface DeletedAccounts {

    boolean isDeleted(UUID userId);
}

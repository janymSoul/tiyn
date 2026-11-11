package kz.tiyn.identity.family;

import java.util.UUID;
import kz.tiyn.identity.account.User;

sealed interface JoinOutcome {

    record Linked(User parent) implements JoinOutcome {}

    record Pending(UUID requestId, User parent) implements JoinOutcome {}
}

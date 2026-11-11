package kz.tiyn.identity.family;

import java.util.UUID;
import kz.tiyn.identity.account.User;

record JoinDecision(UUID requestId, JoinRequestState state, User teen) {}

package kz.tiyn.identity.family.dto;

import java.util.UUID;

public record JoinResponse(Parent parent) {

    public record Parent(UUID id, String displayName) {}
}

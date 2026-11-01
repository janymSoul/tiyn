package kz.tiyn.identity.family.dto;

public record JoinResponse(Parent parent) {

    public record Parent(String displayName) {}
}

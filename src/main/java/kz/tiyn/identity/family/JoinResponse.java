package kz.tiyn.identity.family;

record JoinResponse(Parent parent) {

    record Parent(String displayName) {}
}

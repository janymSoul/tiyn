package kz.tiyn.identity.web;

record JoinResponse(Parent parent) {

    record Parent(String displayName) {}
}

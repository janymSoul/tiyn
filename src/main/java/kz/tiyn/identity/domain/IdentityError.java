package kz.tiyn.identity.domain;

public enum IdentityError {
    INVALID_IDENTITY,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    INVALID_ROLE,
    INVALID_DISPLAY_NAME,
    INVALID_JOIN_CODE,
    INVALID_TEEN_ID,
    CONFLICT;

    public String code() {
        return name().toLowerCase();
    }
}

package kz.tiyn.identity.account.error;

public enum IdentityError {
    INVALID_IDENTITY,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    INVALID_ROLE,
    INVALID_DISPLAY_NAME,
    INVALID_JOIN_CODE,
    INVALID_TEEN_ID,
    CONFLICT,
    IDENTITY_PROVIDER_UNAVAILABLE;

    public String code() {
        return name().toLowerCase();
    }
}

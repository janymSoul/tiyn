package kz.tiyn.identity.account.error;

public enum IdentityError {
    INVALID_IDENTITY,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    INVALID_TRANSITION,
    ROLE_REQUIRED,
    INVALID_ROLE,
    INVALID_DISPLAY_NAME,
    INVALID_JOIN_CODE,
    INVALID_TEEN_ID,
    JOIN_RATE_LIMITED,
    CONFLICT,
    IDENTITY_PROVIDER_UNAVAILABLE;

    public String code() {
        return name().toLowerCase();
    }
}

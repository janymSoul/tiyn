package kz.tiyn.identity.account.error;

public enum IdentityError {
    INVALID_IDENTITY,
    INVALID_REFRESH_TOKEN,
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
    ACCOUNT_HAS_OPEN_PAYOUT,
    IDENTITY_PROVIDER_UNAVAILABLE;

    public String code() {
        return name().toLowerCase();
    }
}

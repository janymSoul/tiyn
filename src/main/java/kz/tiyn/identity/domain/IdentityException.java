package kz.tiyn.identity.domain;

public class IdentityException extends RuntimeException {

    private final IdentityError error;

    public IdentityException(IdentityError error) {
        super(error.code());
        this.error = error;
    }

    public IdentityError error() {
        return error;
    }
}

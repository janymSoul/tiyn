package kz.tiyn.identity.store;

public class ConflictException extends RuntimeException {

    public ConflictException(Throwable cause) {
        super("unique rule violated", cause);
    }
}

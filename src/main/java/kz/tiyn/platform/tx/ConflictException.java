package kz.tiyn.platform.tx;

public class ConflictException extends RuntimeException {

    public ConflictException(Throwable cause) {
        super("unique rule violated", cause);
    }
}

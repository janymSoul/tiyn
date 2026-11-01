package kz.tiyn.identity.google;

public class InvalidGoogleTokenException extends RuntimeException {

    public InvalidGoogleTokenException(Throwable cause) {
        super("invalid Google ID token", cause);
    }
}

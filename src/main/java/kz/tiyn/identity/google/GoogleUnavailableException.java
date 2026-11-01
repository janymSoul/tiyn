package kz.tiyn.identity.google;

public class GoogleUnavailableException extends RuntimeException {

    public GoogleUnavailableException(Throwable cause) {
        super("could not reach Google's key set", cause);
    }
}

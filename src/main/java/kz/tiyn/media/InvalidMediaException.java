package kz.tiyn.media;

public class InvalidMediaException extends RuntimeException {

    public InvalidMediaException(String reason) {
        super(reason);
    }
}

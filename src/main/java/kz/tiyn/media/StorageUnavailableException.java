package kz.tiyn.media;

public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

package kz.tiyn.notification.push.error;

public class NotificationException extends RuntimeException {

    private final NotificationError error;

    public NotificationException(NotificationError error) {
        super(error.code());
        this.error = error;
    }

    public NotificationError error() {
        return error;
    }
}

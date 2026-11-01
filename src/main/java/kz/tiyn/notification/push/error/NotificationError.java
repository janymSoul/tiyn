package kz.tiyn.notification.push.error;

public enum NotificationError {
    INVALID_TOKEN,
    INVALID_PLATFORM,
    INVALID_LOCALE;

    public String code() {
        return name().toLowerCase();
    }
}

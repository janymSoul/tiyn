package kz.tiyn.notification.push;

class PushRejectedException extends RuntimeException {

    PushRejectedException(String message) {
        super(message);
    }
}

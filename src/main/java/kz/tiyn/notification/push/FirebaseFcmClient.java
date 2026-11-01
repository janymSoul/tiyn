package kz.tiyn.notification.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;

class FirebaseFcmClient implements FcmClient {

    private final FirebaseMessaging messaging;
    private final boolean dryRun;

    FirebaseFcmClient(FirebaseMessaging messaging, boolean dryRun) {
        this.messaging = messaging;
        this.dryRun = dryRun;
    }

    @Override
    public void send(Message message) {
        try {
            messaging.send(message, dryRun);
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                throw new PushRejectedException("fcm rejected the token: " + code);
            }
            throw new IllegalStateException("fcm send failed: " + code, e);
        }
    }
}

package kz.tiyn.notification.push;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;

class FcmPushSender implements PushSender {

    private final FcmClient client;

    FcmPushSender(FcmClient client) {
        this.client = client;
    }

    @Override
    public void send(String deviceToken, Push push) {
        client.send(Message.builder()
                .setToken(deviceToken)
                .setNotification(Notification.builder()
                        .setTitle(push.title())
                        .setBody(push.body())
                        .build())
                .putAllData(push.data())
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(push.highPriority() ? AndroidConfig.Priority.HIGH : AndroidConfig.Priority.NORMAL)
                        .build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder().build())
                        .putHeader("apns-priority", push.highPriority() ? "10" : "5")
                        .build())
                .build());
    }
}

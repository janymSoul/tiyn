package kz.tiyn.notification.push;

import com.google.firebase.messaging.Message;

interface FcmClient {

    void send(Message message);
}

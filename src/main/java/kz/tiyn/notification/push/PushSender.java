package kz.tiyn.notification.push;

interface PushSender {

    void send(String deviceToken, Push push);
}

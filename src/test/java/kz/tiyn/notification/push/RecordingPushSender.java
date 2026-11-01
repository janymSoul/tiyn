package kz.tiyn.notification.push;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

public class RecordingPushSender implements PushSender {

    record Sent(String token, Push push) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final Map<String, RuntimeException> failures = new ConcurrentHashMap<>();

    @Override
    public void send(String deviceToken, Push push) {
        RuntimeException failure = failures.get(deviceToken);
        if (failure != null) {
            throw failure;
        }
        sent.add(new Sent(deviceToken, push));
    }

    List<Sent> sentTo(String token) {
        return sent.stream().filter(s -> s.token().equals(token)).toList();
    }

    List<Sent> sentTo(String token, String type) {
        return sentTo(token).stream()
                .filter(s -> type.equals(s.push().data().get("type")))
                .toList();
    }

    void failWith(String token, RuntimeException failure) {
        failures.put(token, failure);
    }

    void stopFailing(String token) {
        failures.remove(token);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        RecordingPushSender recordingPushSender() {
            return new RecordingPushSender();
        }
    }
}

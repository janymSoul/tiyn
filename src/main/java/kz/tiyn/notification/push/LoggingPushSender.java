package kz.tiyn.notification.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tiyn.notification.sender", havingValue = "logging")
class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void send(String deviceToken, Push push) {
        log.info("push type={} priority={}", push.data().get("type"), push.highPriority() ? "high" : "normal");
    }
}

package kz.tiyn.notification.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public void send(String deviceToken, Push push) {
        log.info("push type={} priority={}", push.data().get("type"), push.highPriority() ? "high" : "normal");
    }
}

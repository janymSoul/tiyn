package kz.tiyn.notification.push;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.notification")
record NotificationProperties(Duration deliveredRetention) {}

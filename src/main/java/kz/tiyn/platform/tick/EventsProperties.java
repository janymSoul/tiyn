package kz.tiyn.platform.tick;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.events")
record EventsProperties(Duration resubmitAfter) {}

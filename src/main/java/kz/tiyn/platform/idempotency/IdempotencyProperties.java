package kz.tiyn.platform.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.idempotency")
record IdempotencyProperties(Duration ttl) {}

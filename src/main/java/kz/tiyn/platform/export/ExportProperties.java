package kz.tiyn.platform.export;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.export")
public record ExportProperties(int pageSize, Duration minInterval) {

    public ExportProperties {
        if (pageSize < 1) {
            throw new IllegalArgumentException("tiyn.export.page-size must be at least 1");
        }
        if (minInterval == null || minInterval.isNegative()) {
            throw new IllegalArgumentException("tiyn.export.min-interval must not be negative");
        }
    }
}

package kz.tiyn.media;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tiyn.media.s3")
record S3Properties(
        String endpoint, String region, String bucket, String accessKey, String secretKey, Duration viewUrlTtl) {}

package kz.tiyn.platform;

import java.time.Clock;
import kz.tiyn.platform.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class PlatformConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

package kz.tiyn.identity.app;

import java.security.SecureRandom;
import kz.tiyn.identity.google.GoogleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GoogleProperties.class)
class IdentityConfig {

    @Bean
    SecureRandom joinCodeRandom() {
        return new SecureRandom();
    }
}

package kz.tiyn.identity.account;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AccountConfig {

    @Bean
    SecureRandom joinCodeRandom() {
        return new SecureRandom();
    }
}

package kz.tiyn.support;

import kz.tiyn.identity.domain.IdentityError;
import kz.tiyn.identity.domain.IdentityException;
import kz.tiyn.identity.google.GoogleIdentity;
import kz.tiyn.identity.google.GoogleVerifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FakeGoogleConfig {

    public static String tokenFor(String subject, String name) {
        return "fake|" + subject + "|" + (name == null ? "" : name);
    }

    @Bean
    @Primary
    GoogleVerifier fakeGoogleVerifier() {
        return idToken -> {
            String[] parts = idToken.split("\\|", -1);
            if (parts.length != 3 || !parts[0].equals("fake")) {
                throw new IdentityException(IdentityError.INVALID_IDENTITY);
            }
            return new GoogleIdentity(parts[1], parts[2].isEmpty() ? null : parts[2]);
        };
    }
}

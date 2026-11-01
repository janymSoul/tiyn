package kz.tiyn.notification.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "tiyn.notification.sender", havingValue = "fcm")
@EnableConfigurationProperties(FcmProperties.class)
class FcmConfig {

    @Bean(destroyMethod = "delete")
    FirebaseApp firebaseApp(FcmProperties properties) {
        try (FileInputStream key = new FileInputStream(properties.credentialsFile())) {
            return FirebaseApp.initializeApp(FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(key))
                    .build());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the FCM credentials file", e);
        }
    }

    @Bean
    PushSender fcmPushSender(FirebaseApp app, FcmProperties properties) {
        return new FcmPushSender(new FirebaseFcmClient(FirebaseMessaging.getInstance(app), properties.dryRun()));
    }
}

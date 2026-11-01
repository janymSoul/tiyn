package kz.tiyn.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SenderSelectionTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(LoggingPushSender.class, FcmConfig.class);

    @Test
    void withoutThePropertyThereIsNoSender() {
        runner.run(context -> assertThat(context).doesNotHaveBean(PushSender.class));
    }

    @Test
    void loggingIsSelectedOnlyWhenAskedFor() {
        runner.withPropertyValues("tiyn.notification.sender=logging")
                .run(context -> assertThat(context).hasSingleBean(LoggingPushSender.class));
    }

    @Test
    void fcmWithoutAReadableKeyStopsStartup() {
        runner.withPropertyValues(
                        "tiyn.notification.sender=fcm", "tiyn.notification.fcm.credentials-file=/nonexistent/key.json")
                .run(context -> assertThat(context).hasFailed());
    }
}

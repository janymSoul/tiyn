package kz.tiyn.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kz.tiyn.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(RecordingPushSender.Config.class)
class NotificationServiceTests {

    @Autowired
    NotificationService service;

    @Autowired
    DeviceStore devices;

    @Autowired
    RecordingPushSender sender;

    @Autowired
    JdbcTemplate jdbc;

    private static String token() {
        return "tok-" + UUID.randomUUID();
    }

    private void send(String key, UUID user, NotificationType type) {
        service.notify(key, user, type, List.of("500 ₸"), Map.of("type", type.name()));
    }

    private int delivered(String key, UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notification.delivered WHERE event_key = ? AND recipient_id = ?",
                Integer.class,
                key,
                user);
    }

    @Test
    void everyDeviceGetsTheCopyInItsOwnLanguageOnce() {
        UUID user = UUID.randomUUID();
        String ru = token();
        String kk = token();
        devices.upsert(user, ru, Platform.ANDROID, "ru", Instant.now());
        devices.upsert(user, kk, Platform.IOS, "kk", Instant.now());

        send("k1:" + user, user, NotificationType.TASK_APPROVED);

        assertThat(sender.sentTo(ru))
                .singleElement()
                .satisfies(s -> assertThat(s.push().title()).isEqualTo("Награда начислена"));
        assertThat(sender.sentTo(kk))
                .singleElement()
                .satisfies(s -> assertThat(s.push().title()).isEqualTo("Сыйақы есептелді"));
    }

    @Test
    void aRepeatedEventIsSentOnce() {
        UUID user = UUID.randomUUID();
        String device = token();
        devices.upsert(user, device, Platform.ANDROID, "ru", Instant.now());

        send("dup:" + user, user, NotificationType.TASK_ASSIGNED);
        send("dup:" + user, user, NotificationType.TASK_ASSIGNED);

        assertThat(sender.sentTo(device)).hasSize(1);
        assertThat(delivered("dup:" + user, user)).isEqualTo(1);
    }

    @Test
    void aUserWithoutDevicesCostsNothingAndLeavesNoRecord() {
        UUID user = UUID.randomUUID();

        send("none:" + user, user, NotificationType.TASK_ASSIGNED);

        assertThat(delivered("none:" + user, user)).isZero();
    }

    @Test
    void oneFailingDeviceDoesNotBlockOrRepeatThePushToTheOthers() {
        UUID user = UUID.randomUUID();
        String broken = token();
        String fine = token();
        devices.upsert(user, broken, Platform.ANDROID, "ru", Instant.now());
        devices.upsert(user, fine, Platform.ANDROID, "ru", Instant.now());
        sender.failWith(broken, new IllegalStateException("fcm down"));

        send("mixed:" + user, user, NotificationType.TASK_ASSIGNED);
        send("mixed:" + user, user, NotificationType.TASK_ASSIGNED);

        assertThat(sender.sentTo(fine)).hasSize(1);
        assertThat(delivered("mixed:" + user, user)).isEqualTo(1);
    }

    @Test
    void aTransientFailureLeavesNoRecordAndTheRetrySendsExactlyOnce() {
        UUID user = UUID.randomUUID();
        String device = token();
        devices.upsert(user, device, Platform.ANDROID, "ru", Instant.now());
        sender.failWith(device, new IllegalStateException("fcm down"));

        assertThatThrownBy(() -> send("retry:" + user, user, NotificationType.TASK_ASSIGNED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(delivered("retry:" + user, user)).isZero();

        sender.stopFailing(device);
        send("retry:" + user, user, NotificationType.TASK_ASSIGNED);
        send("retry:" + user, user, NotificationType.TASK_ASSIGNED);

        assertThat(sender.sentTo(device)).hasSize(1);
        assertThat(delivered("retry:" + user, user)).isEqualTo(1);
    }

    @Test
    void aRejectedTokenIsDeletedAndTheOtherDeviceStillGetsThePush() {
        UUID user = UUID.randomUUID();
        String dead = token();
        String alive = token();
        devices.upsert(user, dead, Platform.ANDROID, "ru", Instant.now());
        devices.upsert(user, alive, Platform.ANDROID, "ru", Instant.now());
        sender.failWith(dead, new PushRejectedException("unregistered"));

        send("rej:" + user, user, NotificationType.TASK_ASSIGNED);

        assertThat(devices.findByUser(user)).extracting(DeviceToken::token).containsExactly(alive);
        assertThat(sender.sentTo(alive)).hasSize(1);
        assertThat(delivered("rej:" + user, user)).isEqualTo(1);
    }
}

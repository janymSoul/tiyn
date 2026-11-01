package kz.tiyn.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FcmPushSenderTests {

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static Message sent(boolean high) throws Exception {
        List<Message> sent = new ArrayList<>();
        new FcmPushSender(sent::add).send("token-1", new Push("Title", "Body", Map.of("type", "TASK_ASSIGNED"), high));
        return sent.get(0);
    }

    @Test
    void messageCarriesTokenCopyAndData() throws Exception {
        Message message = sent(false);

        assertThat(field(message, "token")).isEqualTo("token-1");
        assertThat(field(message, "data")).isEqualTo(Map.of("type", "TASK_ASSIGNED"));
        Object notification = field(message, "notification");
        assertThat(field(notification, "title")).isEqualTo("Title");
        assertThat(field(notification, "body")).isEqualTo("Body");
    }

    @Test
    @SuppressWarnings("unchecked")
    void highPriorityPushesAreMarkedHighOnBothPlatforms() throws Exception {
        Message high = sent(true);
        Message normal = sent(false);

        assertThat(field(field(high, "androidConfig"), "priority")).isEqualTo("high");
        assertThat(field(field(normal, "androidConfig"), "priority")).isEqualTo("normal");
        assertThat((Map<Object, Object>) field(field(high, "apnsConfig"), "headers"))
                .containsEntry("apns-priority", "10");
        assertThat((Map<Object, Object>) field(field(normal, "apnsConfig"), "headers"))
                .containsEntry("apns-priority", "5");
    }

    private static FirebaseMessagingException failure(MessagingErrorCode code) {
        FirebaseMessagingException e = mock(FirebaseMessagingException.class);
        when(e.getMessagingErrorCode()).thenReturn(code);
        return e;
    }

    @Test
    void deadTokensBecomeRejections() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        FirebaseMessagingException failure = failure(MessagingErrorCode.UNREGISTERED);
        when(messaging.send(any(Message.class), anyBoolean())).thenThrow(failure);

        assertThatThrownBy(() -> new FirebaseFcmClient(messaging, false)
                        .send(Message.builder().setToken("t").build()))
                .isInstanceOf(PushRejectedException.class);
    }

    @Test
    void transientFailuresStayRetryable() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        FirebaseMessagingException failure = failure(MessagingErrorCode.UNAVAILABLE);
        when(messaging.send(any(Message.class), anyBoolean())).thenThrow(failure);

        assertThatThrownBy(() -> new FirebaseFcmClient(messaging, false)
                        .send(Message.builder().setToken("t").build()))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(PushRejectedException.class);
    }

    @Test
    void dryRunIsPassedToTheSdk() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        Message message = Message.builder().setToken("t").build();

        new FirebaseFcmClient(messaging, true).send(message);

        verify(messaging).send(message, true);
    }
}

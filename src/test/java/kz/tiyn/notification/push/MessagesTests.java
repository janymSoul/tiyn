package kz.tiyn.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessagesTests {

    private final Messages messages = new Messages();

    @Test
    void everyTypeHasCopyInBothLanguagesWithNoUnresolvedPlaceholder() {
        for (NotificationType type : NotificationType.values()) {
            for (String locale : List.of("ru", "kk")) {
                Push push = messages.render(type, locale, List.of("500 ₸"), Map.of("type", type.name()));

                assertThat(push.title()).as(type + "/" + locale + " title").isNotBlank();
                assertThat(push.body()).as(type + "/" + locale + " body").isNotBlank();
                assertThat(push.title() + push.body()).doesNotContain("{", "}", ".title", ".body");
            }
        }
    }

    @Test
    void thePlaceholderIsFilledAndLanguagesDiffer() {
        Push ru = messages.render(NotificationType.TASK_APPROVED, "ru", List.of("500 ₸"), Map.of());
        Push kk = messages.render(NotificationType.TASK_APPROVED, "kk", List.of("500 ₸"), Map.of());

        assertThat(ru.body()).contains("500 ₸");
        assertThat(kk.body()).contains("500 ₸");
        assertThat(ru.title()).isNotEqualTo(kk.title());
    }

    @Test
    void anUnknownLocaleFallsBackToRussian() {
        Push unknown = messages.render(NotificationType.TASK_CANCELLED, "de", List.of(), Map.of());
        Push ru = messages.render(NotificationType.TASK_CANCELLED, "ru", List.of(), Map.of());

        assertThat(unknown).isEqualTo(ru);
    }

    @Test
    void onlyThePayoutRequestIsHighPriority() {
        for (NotificationType type : NotificationType.values()) {
            Push push = messages.render(type, "ru", List.of("1"), Map.of());
            assertThat(push.highPriority()).isEqualTo(type == NotificationType.PAYOUT_REQUESTED);
        }
    }

    @Test
    void longNotesAreShortened() {
        assertThat(Messages.shorten("  ok  ")).isEqualTo("ok");
        assertThat(Messages.shorten(null)).isEmpty();
        String shortened = Messages.shorten("x".repeat(300));
        assertThat(shortened).hasSize(Messages.MAX_NOTE).endsWith("…");
    }
}

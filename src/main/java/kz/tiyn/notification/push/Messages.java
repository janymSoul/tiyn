package kz.tiyn.notification.push;

import java.util.List;
import java.util.Locale;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

@Component
class Messages {

    static final String RU = "ru";
    static final String KK = "kk";
    static final int MAX_NOTE = 100;

    private final ResourceBundleMessageSource source = new ResourceBundleMessageSource();

    Messages() {
        source.setBasename("notification/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
    }

    Push render(NotificationType type, String locale, List<String> args, java.util.Map<String, String> data) {
        Locale target = KK.equals(locale) ? Locale.forLanguageTag(KK) : Locale.forLanguageTag(RU);
        Object[] values = args.toArray();
        return new Push(
                source.getMessage(type.code() + ".title", values, target),
                source.getMessage(type.code() + ".body", values, target).trim(),
                data,
                type.highPriority());
    }

    static String shorten(String note) {
        if (note == null) {
            return "";
        }
        String trimmed = note.trim();
        return trimmed.length() <= MAX_NOTE ? trimmed : trimmed.substring(0, MAX_NOTE - 1) + "…";
    }
}

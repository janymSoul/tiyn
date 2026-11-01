package kz.tiyn.platform.tx;

import java.util.function.Supplier;

public final class ConflictRetry {

    private static final int MAX_ATTEMPTS = 5;

    private ConflictRetry() {}

    public static <T> T run(Supplier<T> work, Supplier<? extends RuntimeException> onExhausted) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (ConflictException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw onExhausted.get();
                }
            }
        }
    }
}

package kz.tiyn.identity.account;

import java.util.regex.Pattern;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;

record DisplayName(String value) {

    static final int MAX_LENGTH = 100;

    private static final Pattern SPACE_RUN = Pattern.compile(" {2,}");

    static DisplayName parse(String raw) {
        String normalised = normalise(raw);
        if (normalised.isEmpty() || normalised.length() > MAX_LENGTH) {
            throw new IdentityException(IdentityError.INVALID_DISPLAY_NAME);
        }
        return new DisplayName(normalised);
    }

    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        int[] kept = raw.codePoints()
                .filter(DisplayName::isKept)
                .map(DisplayName::spaceForSeparators)
                .toArray();
        return SPACE_RUN
                .matcher(new String(kept, 0, kept.length))
                .replaceAll(" ")
                .strip();
    }

    private static boolean isKept(int codePoint) {
        return isSeparator(codePoint) || isPrintable(codePoint);
    }

    private static int spaceForSeparators(int codePoint) {
        return isSeparator(codePoint) ? ' ' : codePoint;
    }

    private static boolean isSeparator(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isPrintable(int codePoint) {
        int type = Character.getType(codePoint);
        return type != Character.CONTROL && type != Character.FORMAT && type != Character.SURROGATE;
    }
}

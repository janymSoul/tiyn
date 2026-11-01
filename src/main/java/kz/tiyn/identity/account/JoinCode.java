package kz.tiyn.identity.account;

import java.security.SecureRandom;
import java.util.Locale;

record JoinCode(String value) {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 6;

    static JoinCode generate(SecureRandom random) {
        var code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return new JoinCode(code.toString());
    }

    static JoinCode parse(String raw) {
        if (raw == null) {
            throw new IdentityException(IdentityError.INVALID_JOIN_CODE);
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        boolean valid = normalized.length() == LENGTH && normalized.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
        if (!valid) {
            throw new IdentityException(IdentityError.INVALID_JOIN_CODE);
        }
        return new JoinCode(normalized);
    }
}

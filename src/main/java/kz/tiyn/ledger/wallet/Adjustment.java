package kz.tiyn.ledger.wallet;

import kz.tiyn.ledger.AdjustmentKind;
import kz.tiyn.ledger.wallet.dto.AdjustmentRequest;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;

record Adjustment(AdjustmentKind kind, long amount, String reason) {

    static final int MAX_REASON_LENGTH = 140;

    static Adjustment of(AdjustmentRequest request, long maxAmount) {
        AdjustmentKind kind = parseKind(request.kind());
        Long amount = request.amount();
        if (amount == null || amount <= 0 || amount > maxAmount) {
            throw new LedgerException(LedgerError.INVALID_AMOUNT);
        }
        return new Adjustment(kind, amount, normalise(request.reason()));
    }

    EntryKind entryKind() {
        return kind == AdjustmentKind.BONUS ? EntryKind.MANUAL_BONUS : EntryKind.MANUAL_PENALTY;
    }

    long balanceDelta() {
        return kind == AdjustmentKind.BONUS ? amount : -amount;
    }

    private static AdjustmentKind parseKind(String kind) {
        try {
            return AdjustmentKind.valueOf(kind == null ? "" : kind);
        } catch (IllegalArgumentException e) {
            throw new LedgerException(LedgerError.INVALID_KIND);
        }
    }

    private static String normalise(String reason) {
        if (reason == null) {
            throw new LedgerException(LedgerError.INVALID_REASON);
        }
        StringBuilder cleaned = new StringBuilder(reason.length());
        reason.codePoints().forEach(codePoint -> {
            if (Character.isWhitespace(codePoint)) {
                cleaned.append(' ');
            } else if (!isInvisible(codePoint)) {
                cleaned.appendCodePoint(codePoint);
            }
        });
        String stripped = cleaned.toString().strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length == 0 || length > MAX_REASON_LENGTH) {
            throw new LedgerException(LedgerError.INVALID_REASON);
        }
        return stripped;
    }

    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.UNASSIGNED
                || type == Character.PRIVATE_USE
                || type == Character.SURROGATE;
    }
}

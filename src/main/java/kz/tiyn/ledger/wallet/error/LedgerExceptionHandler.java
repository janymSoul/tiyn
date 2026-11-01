package kz.tiyn.ledger.wallet.error;

import kz.tiyn.platform.web.ErrorBody;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "kz.tiyn.ledger")
class LedgerExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<ErrorBody> handle(LedgerException e) {
        return ResponseEntity.status(statusOf(e)).body(new ErrorBody(e.error().code()));
    }

    private static HttpStatus statusOf(LedgerException e) {
        return switch (e.error()) {
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_AMOUNT,
                    UNSUPPORTED_CURRENCY,
                    AMOUNT_TOO_SMALL,
                    INSUFFICIENT_FUNDS,
                    PARENT_REQUIRED,
                    INVALID_IDEMPOTENCY_KEY,
                    INVALID_FILTER -> HttpStatus.UNPROCESSABLE_ENTITY;
            case PAYOUT_ALREADY_OPEN, INVALID_TRANSITION -> HttpStatus.CONFLICT;
        };
    }
}

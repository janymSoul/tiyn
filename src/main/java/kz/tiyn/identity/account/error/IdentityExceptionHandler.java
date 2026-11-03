package kz.tiyn.identity.account.error;

import kz.tiyn.platform.web.ErrorBody;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "kz.tiyn.identity")
class IdentityExceptionHandler {

    @ExceptionHandler(IdentityException.class)
    ResponseEntity<ErrorBody> handle(IdentityException e) {
        return ResponseEntity.status(statusOf(e)).body(new ErrorBody(e.error().code()));
    }

    private static HttpStatus statusOf(IdentityException e) {
        return switch (e.error()) {
            case INVALID_IDENTITY, UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ROLE_REQUIRED, INVALID_ROLE, INVALID_DISPLAY_NAME, INVALID_JOIN_CODE -> HttpStatus
                    .UNPROCESSABLE_ENTITY;
            case INVALID_TEEN_ID -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case IDENTITY_PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}

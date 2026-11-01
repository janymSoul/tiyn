package kz.tiyn.notification.push.error;

import kz.tiyn.platform.web.ErrorBody;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "kz.tiyn.notification")
class NotificationExceptionHandler {

    @ExceptionHandler(NotificationException.class)
    ResponseEntity<ErrorBody> handle(NotificationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorBody(e.error().code()));
    }
}

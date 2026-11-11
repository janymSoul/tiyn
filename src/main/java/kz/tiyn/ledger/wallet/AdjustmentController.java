package kz.tiyn.ledger.wallet;

import java.util.UUID;
import kz.tiyn.ledger.wallet.dto.AdjustmentRequest;
import kz.tiyn.ledger.wallet.dto.AdjustmentResponse;
import kz.tiyn.platform.idempotency.IdempotencyContext;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdjustmentController {

    private final AdjustmentService service;

    AdjustmentController(AdjustmentService service) {
        this.service = service;
    }

    @PostMapping("/teens/{teenId}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    AdjustmentResponse adjust(
            @AuthenticationPrincipal Principal caller,
            @PathVariable UUID teenId,
            IdempotencyContext idempotency,
            @RequestBody AdjustmentRequest request) {
        return toResponse(service.adjust(caller, idempotency, teenId, request));
    }

    private static AdjustmentResponse toResponse(AdjustmentView view) {
        return new AdjustmentResponse(
                view.id(),
                view.teenId(),
                view.parentId(),
                view.balanceDelta() > 0 ? "BONUS" : "PENALTY",
                Math.abs(view.balanceDelta()),
                view.currency(),
                view.reason(),
                view.createdAt());
    }
}

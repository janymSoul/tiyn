package kz.tiyn.ledger.wallet;

import java.util.List;
import java.util.UUID;
import kz.tiyn.ledger.wallet.dto.PayoutResponse;
import kz.tiyn.ledger.wallet.dto.RequestPayoutRequest;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.idempotency.NaturallyIdempotent;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PayoutController {

    private final PayoutService service;

    PayoutController(PayoutService service) {
        this.service = service;
    }

    @PostMapping("/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    PayoutResponse request(
            @AuthenticationPrincipal Principal caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody RequestPayoutRequest request) {
        return toResponse(service.request(caller, request.parentId(), request.amount(), key));
    }

    @GetMapping("/payouts")
    List<PayoutResponse> list(@AuthenticationPrincipal Principal caller, @RequestParam(required = false) String state) {
        return service.list(caller, parseState(state)).stream()
                .map(PayoutController::toResponse)
                .toList();
    }

    @GetMapping("/payouts/{id}")
    PayoutResponse get(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return toResponse(service.get(caller, id));
    }

    @PostMapping("/payouts/{id}/fulfil")
    @NaturallyIdempotent
    PayoutResponse fulfil(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return toResponse(service.fulfil(caller, id));
    }

    @PostMapping("/payouts/{id}/cancel")
    @NaturallyIdempotent
    PayoutResponse cancel(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return toResponse(service.cancel(caller, id));
    }

    @PostMapping("/payouts/{id}/decline")
    @NaturallyIdempotent
    PayoutResponse decline(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return toResponse(service.decline(caller, id));
    }

    private static PayoutState parseState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return PayoutState.valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new LedgerException(LedgerError.INVALID_FILTER);
        }
    }

    private static PayoutResponse toResponse(Payout p) {
        return new PayoutResponse(
                p.id(),
                p.teenId(),
                p.parentId(),
                p.amount(),
                p.currency(),
                p.state().name(),
                p.requestedAt(),
                p.resolvedAt(),
                p.cancelReason() == null ? null : p.cancelReason().name());
    }
}

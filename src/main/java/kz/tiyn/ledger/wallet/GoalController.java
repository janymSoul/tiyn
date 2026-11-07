package kz.tiyn.ledger.wallet;

import java.util.List;
import java.util.UUID;
import kz.tiyn.ledger.wallet.dto.AmountRequest;
import kz.tiyn.ledger.wallet.dto.CreateGoalRequest;
import kz.tiyn.ledger.wallet.dto.GoalResponse;
import kz.tiyn.platform.idempotency.NaturallyIdempotent;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class GoalController {

    private final GoalService service;

    GoalController(GoalService service) {
        this.service = service;
    }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    GoalResponse create(@AuthenticationPrincipal Principal caller, @RequestBody CreateGoalRequest request) {
        return toResponse(service.create(caller, request.name(), request.targetAmount()));
    }

    @GetMapping("/goals")
    List<GoalResponse> list(@AuthenticationPrincipal Principal caller) {
        return service.list(caller).stream().map(GoalController::toResponse).toList();
    }

    @GetMapping("/goals/{id}")
    GoalResponse get(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return toResponse(service.get(caller, id));
    }

    @PostMapping("/goals/{id}/lock")
    GoalResponse lock(
            @AuthenticationPrincipal Principal caller,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody AmountRequest request) {
        return toResponse(service.lock(caller, id, request.amount(), key));
    }

    @PostMapping("/goals/{id}/unlock")
    GoalResponse unlock(
            @AuthenticationPrincipal Principal caller,
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody AmountRequest request) {
        return toResponse(service.unlock(caller, id, request.amount(), key));
    }

    @PostMapping("/goals/{id}/close")
    @NaturallyIdempotent
    GoalResponse close(@AuthenticationPrincipal Principal caller, @PathVariable UUID id) {
        return toResponse(service.close(caller, id));
    }

    private static GoalResponse toResponse(GoalView view) {
        Goal g = view.goal();
        return new GoalResponse(
                g.id(),
                g.name(),
                g.targetAmount(),
                view.savedAmount(),
                g.currency(),
                g.state().name());
    }
}

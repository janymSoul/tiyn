package kz.tiyn.identity.family;

import java.util.UUID;
import kz.tiyn.identity.family.dto.JoinDecisionResponse;
import kz.tiyn.identity.family.dto.PendingJoinResponse;
import kz.tiyn.platform.idempotency.NaturallyIdempotent;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class JoinRequestController {

    private final JoinRequestService service;

    JoinRequestController(JoinRequestService service) {
        this.service = service;
    }

    @GetMapping("/links/requests")
    Page<PendingJoinResponse> pending(@AuthenticationPrincipal Principal principal, PageRequest page) {
        return service.pending(principal.userId(), principal.role(), page)
                .map(r -> new PendingJoinResponse(r.id(), r.teenId(), r.teenDisplayName(), r.createdAt()));
    }

    @PostMapping("/links/requests/{id}/approve")
    @NaturallyIdempotent
    JoinDecisionResponse approve(@AuthenticationPrincipal Principal principal, @PathVariable UUID id) {
        return toResponse(service.approve(principal.userId(), id));
    }

    @PostMapping("/links/requests/{id}/decline")
    @NaturallyIdempotent
    JoinDecisionResponse decline(@AuthenticationPrincipal Principal principal, @PathVariable UUID id) {
        return toResponse(service.decline(principal.userId(), id));
    }

    @DeleteMapping("/links/requests/{id}")
    ResponseEntity<Void> withdraw(@AuthenticationPrincipal Principal principal, @PathVariable UUID id) {
        service.withdraw(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }

    private static JoinDecisionResponse toResponse(JoinDecision decision) {
        return new JoinDecisionResponse(
                decision.requestId(),
                decision.state().name(),
                new JoinDecisionResponse.Teen(
                        decision.teen().id(), decision.teen().displayName()));
    }
}

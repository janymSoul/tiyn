package kz.tiyn.identity.family;

import java.util.UUID;
import kz.tiyn.identity.account.error.IdentityError;
import kz.tiyn.identity.account.error.IdentityException;
import kz.tiyn.identity.family.dto.JoinRequest;
import kz.tiyn.identity.family.dto.JoinResponse;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class FamilyController {

    private final FamilyService service;

    FamilyController(FamilyService service) {
        this.service = service;
    }

    @PostMapping("/links/join")
    JoinResponse join(@AuthenticationPrincipal Principal principal, @RequestBody JoinRequest request) {
        return new JoinResponse(new JoinResponse.Parent(service.join(principal.userId(), request.code())));
    }

    @DeleteMapping("/links/{teenId}")
    ResponseEntity<Void> unlink(@AuthenticationPrincipal Principal principal, @PathVariable String teenId) {
        service.unlink(principal.userId(), parseTeenId(teenId));
        return ResponseEntity.noContent().build();
    }

    private static UUID parseTeenId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IdentityException(IdentityError.INVALID_TEEN_ID);
        }
    }
}

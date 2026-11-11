package kz.tiyn.identity.deletion;

import kz.tiyn.platform.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountDeletionController {

    private final AccountDeletionService service;

    AccountDeletionController(AccountDeletionService service) {
        this.service = service;
    }

    @DeleteMapping("/me")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Principal principal) {
        service.delete(principal.userId());
        return ResponseEntity.noContent().build();
    }
}

package kz.tiyn.identity.web;

import java.util.UUID;
import kz.tiyn.identity.app.IdentityService;
import kz.tiyn.identity.app.SignInResult;
import kz.tiyn.identity.domain.IdentityError;
import kz.tiyn.identity.domain.IdentityException;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class IdentityController {

    private final IdentityService service;

    IdentityController(IdentityService service) {
        this.service = service;
    }

    @PostMapping("/auth/google")
    SignInResponse signIn(@RequestBody SignInRequest request) {
        SignInResult result = service.signIn(request.idToken(), request.role(), request.displayName());
        return new SignInResponse(result.token(), UserResponse.from(result.user()));
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal Principal principal) {
        return UserResponse.from(service.me(principal.userId()));
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

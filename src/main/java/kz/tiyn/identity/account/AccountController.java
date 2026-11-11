package kz.tiyn.identity.account;

import kz.tiyn.identity.account.dto.RefreshRequest;
import kz.tiyn.identity.account.dto.SignInRequest;
import kz.tiyn.identity.account.dto.SignInResponse;
import kz.tiyn.identity.account.dto.SignInResult;
import kz.tiyn.identity.account.dto.UserResponse;
import kz.tiyn.platform.idempotency.NaturallyIdempotent;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

    private final AccountService service;
    private final SessionService sessions;

    AccountController(AccountService service, SessionService sessions) {
        this.service = service;
        this.sessions = sessions;
    }

    @PostMapping("/auth/google")
    @NaturallyIdempotent
    SignInResponse signIn(@RequestBody SignInRequest request) {
        return toResponse(service.signIn(request.idToken(), request.role(), request.displayName()));
    }

    private static SignInResponse toResponse(SignInResult result) {
        return new SignInResponse(result.token(), UserResponse.from(result.user()), result.refreshToken());
    }

    @PostMapping("/auth/refresh")
    @NaturallyIdempotent
    SignInResponse refresh(@RequestBody RefreshRequest request) {
        return toResponse(sessions.refresh(request.refreshToken()));
    }

    @PostMapping("/auth/logout")
    @NaturallyIdempotent
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody RefreshRequest request) {
        sessions.logout(request.refreshToken());
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal Principal principal) {
        return UserResponse.from(service.requireUser(principal.userId()));
    }
}

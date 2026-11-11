package kz.tiyn.identity.account;

import jakarta.validation.Valid;
import kz.tiyn.identity.account.dto.JoinApprovalRequest;
import kz.tiyn.identity.account.dto.SignInRequest;
import kz.tiyn.identity.account.dto.SignInResponse;
import kz.tiyn.identity.account.dto.SignInResult;
import kz.tiyn.identity.account.dto.UpdateProfileRequest;
import kz.tiyn.identity.account.dto.UserResponse;
import kz.tiyn.platform.idempotency.NaturallyIdempotent;
import kz.tiyn.platform.security.Principal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping("/auth/google")
    @NaturallyIdempotent
    SignInResponse signIn(@RequestBody SignInRequest request) {
        SignInResult result = service.signIn(request.idToken(), request.role(), request.displayName());
        return new SignInResponse(result.token(), UserResponse.from(result.user()));
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal Principal principal) {
        return UserResponse.from(service.requireUser(principal.userId()));
    }

    @PutMapping("/me/join-approval")
    UserResponse changeJoinApproval(
            @AuthenticationPrincipal Principal principal, @Valid @RequestBody JoinApprovalRequest request) {
        return UserResponse.from(service.changeJoinApproval(principal.userId(), request.required()));
    }

    @PatchMapping("/me")
    UserResponse updateProfile(
            @AuthenticationPrincipal Principal principal, @RequestBody UpdateProfileRequest request) {
        return UserResponse.from(service.updateProfile(principal.userId(), request.displayName()));
    }

    @PostMapping("/me/join-code/regenerate")
    @NaturallyIdempotent
    UserResponse regenerateJoinCode(@AuthenticationPrincipal Principal principal) {
        return UserResponse.from(service.regenerateJoinCode(principal.userId()));
    }
}

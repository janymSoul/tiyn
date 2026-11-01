package kz.tiyn.dashboard.view;

import kz.tiyn.dashboard.view.dto.DashboardResponse;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DashboardController {

    private final DashboardService service;

    DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    DashboardResponse dashboard(@AuthenticationPrincipal Principal caller) {
        if (caller.role() != Role.TEEN) {
            throw new AccessDeniedException("teens only");
        }
        return service.forTeen(caller.userId());
    }
}

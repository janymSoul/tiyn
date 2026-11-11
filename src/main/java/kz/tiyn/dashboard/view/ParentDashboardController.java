package kz.tiyn.dashboard.view;

import kz.tiyn.dashboard.view.dto.ParentDashboardResponse;
import kz.tiyn.platform.paging.BoundedList;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ParentDashboardController {

    private final ParentDashboardService service;

    ParentDashboardController(ParentDashboardService service) {
        this.service = service;
    }

    @GetMapping("/parent/dashboard")
    @BoundedList("a parent is linked to a handful of teens; each preview is capped by tiyn.dashboard.review-preview")
    ParentDashboardResponse dashboard(@AuthenticationPrincipal Principal caller) {
        if (caller.role() != Role.PARENT) {
            throw new AccessDeniedException("parents only");
        }
        return service.forParent(caller.userId());
    }
}

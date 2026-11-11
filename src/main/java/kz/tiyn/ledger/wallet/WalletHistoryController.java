package kz.tiyn.ledger.wallet;

import java.util.UUID;
import kz.tiyn.ledger.wallet.dto.EntryResponse;
import kz.tiyn.platform.paging.Page;
import kz.tiyn.platform.paging.PageRequest;
import kz.tiyn.platform.security.Principal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WalletHistoryController {

    private final WalletHistoryService service;

    WalletHistoryController(WalletHistoryService service) {
        this.service = service;
    }

    @GetMapping("/wallet/entries")
    Page<EntryResponse> entries(
            @AuthenticationPrincipal Principal caller, @RequestParam(required = false) UUID teenId, PageRequest page) {
        return service.entries(caller, teenId, page).map(WalletHistoryController::toResponse);
    }

    private static EntryResponse toResponse(EntryView view) {
        LedgerEntry entry = view.entry();
        return new EntryResponse(
                entry.id(),
                entry.kind().name(),
                view.availableEffect(),
                entry.currency(),
                view.createdAt(),
                entry.kind().relatesToTask() ? entry.refId() : null,
                entry.kind() == EntryKind.PAYOUT ? entry.refId() : null,
                entry.kind().relatesToGoal() ? entry.refId() : null);
    }
}

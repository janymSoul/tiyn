package kz.tiyn.ledger.wallet;

import kz.tiyn.ledger.Balance;
import kz.tiyn.ledger.BalanceReader;
import kz.tiyn.ledger.wallet.dto.BalanceResponse;
import kz.tiyn.ledger.wallet.error.LedgerError;
import kz.tiyn.ledger.wallet.error.LedgerException;
import kz.tiyn.platform.security.Principal;
import kz.tiyn.platform.security.Role;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BalanceController {

    private final BalanceReader balances;

    BalanceController(BalanceReader balances) {
        this.balances = balances;
    }

    @GetMapping("/balance")
    BalanceResponse balance(@AuthenticationPrincipal Principal caller) {
        if (caller.role() != Role.TEEN) {
            throw new LedgerException(LedgerError.FORBIDDEN);
        }
        Balance balance = balances.balance(caller.userId());
        return new BalanceResponse(
                balance.balanceAmount(), balance.lockedAmount(), balance.availableAmount(), balance.currency());
    }
}

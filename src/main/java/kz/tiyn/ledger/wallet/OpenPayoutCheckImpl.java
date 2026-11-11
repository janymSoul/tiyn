package kz.tiyn.ledger.wallet;

import java.util.UUID;
import kz.tiyn.identity.OpenPayoutCheck;
import kz.tiyn.platform.security.Role;
import org.springframework.stereotype.Component;

@Component
class OpenPayoutCheckImpl implements OpenPayoutCheck {

    private final PayoutStore payouts;

    OpenPayoutCheckImpl(PayoutStore payouts) {
        this.payouts = payouts;
    }

    @Override
    public boolean hasOpenPayout(UUID userId, Role role) {
        return role == Role.TEEN ? payouts.hasOpen(userId) : payouts.hasOpenAddressedTo(userId);
    }
}

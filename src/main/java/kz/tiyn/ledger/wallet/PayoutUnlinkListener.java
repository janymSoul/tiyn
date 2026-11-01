package kz.tiyn.ledger.wallet;

import kz.tiyn.identity.FamilyUnlinked;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class PayoutUnlinkListener {

    private final PayoutService service;

    PayoutUnlinkListener(PayoutService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    void on(FamilyUnlinked event) {
        service.cancelAfterUnlink(event.parentId(), event.teenId());
    }
}

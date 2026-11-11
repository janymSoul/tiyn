package kz.tiyn.notification.push;

import kz.tiyn.identity.AccountDeleted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
class AccountDeletedListener {

    private final DeviceService devices;

    AccountDeletedListener(DeviceService devices) {
        this.devices = devices;
    }

    @ApplicationModuleListener
    void on(AccountDeleted event) {
        devices.unregisterAll(event.userId());
    }
}

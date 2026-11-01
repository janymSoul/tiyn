package kz.tiyn.notification.push;

import kz.tiyn.notification.push.dto.RegisterDeviceRequest;
import kz.tiyn.platform.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DeviceController {

    private final DeviceService service;

    DeviceController(DeviceService service) {
        this.service = service;
    }

    @PutMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void register(@AuthenticationPrincipal Principal caller, @RequestBody RegisterDeviceRequest request) {
        service.register(caller.userId(), request.token(), request.platform(), request.locale());
    }

    @DeleteMapping("/devices")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unregister(@AuthenticationPrincipal Principal caller, @RequestParam String token) {
        service.unregister(caller.userId(), token);
    }
}

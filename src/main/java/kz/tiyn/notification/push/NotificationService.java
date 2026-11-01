package kz.tiyn.notification.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final DeviceStore devices;
    private final DeliveredStore delivered;
    private final Messages messages;
    private final PushSender sender;

    NotificationService(DeviceStore devices, DeliveredStore delivered, Messages messages, PushSender sender) {
        this.devices = devices;
        this.delivered = delivered;
        this.messages = messages;
        this.sender = sender;
    }

    @Transactional
    void notify(String eventKey, UUID recipientId, NotificationType type, List<String> args, Map<String, String> data) {
        List<DeviceToken> recipientDevices = devices.findByUser(recipientId);
        if (recipientDevices.isEmpty() || !delivered.claim(eventKey, recipientId)) {
            return;
        }
        int sent = 0;
        List<RuntimeException> failures = new ArrayList<>();
        for (DeviceToken device : recipientDevices) {
            try {
                sender.send(device.token(), messages.render(type, device.locale(), args, data));
                sent++;
            } catch (PushRejectedException e) {
                log.info("device token rejected, removing it: {}", e.getMessage());
                devices.deleteToken(device.token());
            } catch (RuntimeException e) {
                log.warn("push to one device failed: {}", e.toString());
                failures.add(e);
            }
        }
        if (sent == 0 && !failures.isEmpty()) {
            throw failures.get(0);
        }
    }
}

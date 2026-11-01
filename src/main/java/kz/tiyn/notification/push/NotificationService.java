package kz.tiyn.notification.push;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    void notify(String eventKey, UUID recipientId, NotificationType type, List<String> args, Map<String, String> data) {
        if (delivered.exists(eventKey, recipientId)) {
            return;
        }
        for (DeviceToken device : devices.findByUser(recipientId)) {
            Push push = messages.render(type, device.locale(), args, data);
            try {
                sender.send(device.token(), push);
            } catch (PushRejectedException e) {
                log.info("device token rejected, removing it: {}", e.getMessage());
                devices.deleteToken(device.token());
            }
        }
        delivered.mark(eventKey, recipientId);
    }
}

package kz.tiyn.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class DomainEvents {

    private final ApplicationEventPublisher publisher;

    DomainEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publishAll(List<Object> events) {
        events.forEach(publisher::publishEvent);
    }
}

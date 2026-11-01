package kz.tiyn.platform.tick;

import java.time.Instant;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Component;

@Component
class EventResubmitTicker implements Ticker {

    private final IncompleteEventPublications publications;
    private final EventsProperties properties;

    EventResubmitTicker(IncompleteEventPublications publications, EventsProperties properties) {
        this.publications = publications;
        this.properties = properties;
    }

    @Override
    public void tick(Instant now) {
        publications.resubmitIncompletePublicationsOlderThan(properties.resubmitAfter());
    }
}

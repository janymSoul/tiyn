package kz.tiyn.platform.event;

import java.util.ArrayList;
import java.util.List;

public final class EventLog {

    private final List<Object> events = new ArrayList<>();

    public void record(Object event) {
        events.add(event);
    }

    public List<Object> drain() {
        List<Object> raised = List.copyOf(events);
        events.clear();
        return raised;
    }
}

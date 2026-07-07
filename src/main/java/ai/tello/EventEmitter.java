package ai.tello;

import ai.tello.events.TelloEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Minimal thread-safe pub/sub registry keyed by event type string. */
public class EventEmitter {

    private static final System.Logger LOG = System.getLogger("tello");

    private final Map<String, List<Consumer<TelloEvent>>> handlers = new ConcurrentHashMap<>();

    /** Register {@code handler} for {@code eventType}. */
    public void on(String eventType, Consumer<TelloEvent> handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** Remove a previously registered handler (no-op if absent). */
    public void off(String eventType, Consumer<TelloEvent> handler) {
        List<Consumer<TelloEvent>> list = handlers.get(eventType);
        if (list != null) {
            list.remove(handler);
        }
    }

    /** Invoke every handler for {@code eventType}; a throwing handler is logged, not fatal. */
    protected void emit(String eventType, TelloEvent event) {
        List<Consumer<TelloEvent>> list = handlers.get(eventType);
        if (list == null) {
            return;
        }
        for (Consumer<TelloEvent> handler : list) {
            try {
                handler.accept(event);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "tello: event handler for " + eventType + " threw", e);
            }
        }
    }
}

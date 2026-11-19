package why.lang;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code flash} global: messages carried from one request to the next.
 * Values loaded from the session are readable here; values set during this
 * request wait in {@link #pending()} for the server to save.
 */
public final class Flash {
    private final Map<String, Object> carried;
    private final Map<String, Object> pending = new LinkedHashMap<>();

    public Flash(Map<String, Object> carried) {
        this.carried = carried;
    }

    public Object get(String key) {
        return pending.containsKey(key) ? pending.get(key) : carried.get(key);
    }

    public void set(String key, Object value) {
        pending.put(key, value);
    }

    /** A copy of the messages to keep for the next request. */
    public Map<String, Object> pending() {
        return new LinkedHashMap<>(pending);
    }
}

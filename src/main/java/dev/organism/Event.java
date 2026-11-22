package dev.organism;

import java.util.*;

/** Запись «журнала эволюции» — всё, что организм сделал с собой, и почему. */
public record Event(long tick, String type, String subject, Map<String, Object> data) {
    public Map<String, Object> toMap() {
        var m = new LinkedHashMap<String, Object>();
        m.put("t", tick); m.put("ev", type); m.put("who", subject); m.putAll(data);
        return m;
    }
    static Event of(long t, String type, String who, Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new Event(t, type, who, m);
    }
}

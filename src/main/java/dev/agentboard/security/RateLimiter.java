package dev.agentboard.security;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed one-minute windows per key, kept in memory. Enough for one process; with many copies
 * the same interface would be backed by Redis so the limit is shared.
 * The cropped windows.compute expression was completed from the visible count/window contract.
 */
@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, int perMinute) {
        long minute = System.currentTimeMillis() / 60_000;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute != minute
                ? new Window(minute, 1) : new Window(minute, old.count + 1));
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute != minute);
        }
        return w.count <= perMinute;
    }

    /** True when the key has already used up this minute's allowance (does not count as a use). */
    public boolean exhausted(String key, int perMinute) {
        Window w = windows.get(key);
        return w != null && w.minute == System.currentTimeMillis() / 60_000 && w.count >= perMinute;
    }

    private record Window(long minute, int count) {
    }
}

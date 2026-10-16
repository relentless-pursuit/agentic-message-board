package dev.agentboard.connect;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * After a few failures in a row, stop calling a host for a while. A broken outside system then
 * costs one quick error instead of a 10-second timeout in every run that uses it.
 */
public class CircuitBreaker {

    private final int failuresToOpen;
    private final Duration openFor;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public CircuitBreaker(int failuresToOpen, Duration openFor) {
        this.failuresToOpen = failuresToOpen;
        this.openFor = openFor;
    }

    public void check(String host) {
        State s = states.get(host);
        if (s != null && s.openUntil != null && Instant.now().isBefore(s.openUntil)) {
            throw new IllegalStateException("skipping " + host + ": it failed " + s.failures
                    + " times in a row; trying again after " + s.openUntil);
        }
    }

    public void success(String host) {
        states.remove(host);
    }

    public void failure(String host) {
        states.compute(host, (h, old) -> {
            int failures = old == null ? 1 : old.failures + 1;
            Instant until = failures >= failuresToOpen ? Instant.now().plus(openFor) : null;
            return new State(failures, until);
        });
    }

    private record State(int failures, Instant openUntil) {
    }
}

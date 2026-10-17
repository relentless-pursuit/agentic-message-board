package dev.agentboard.run;

import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a person asked to stop. A running agent checks this before every model request and tool call, so it stops
 * within one step; the request already in flight finishes and is paid for, nothing after it starts.
 */
@Component
public class RunCancellation {
    private final Set<Long> stopped = ConcurrentHashMap.newKeySet();
    public void stop(long runId) { stopped.add(runId); }
    public boolean stopped(long runId) { return stopped.contains(runId); }
    /** Throws when the run was stopped; call it between steps. */
    public void check(long runId) { if (stopped(runId)) throw new StoppedException(); }
    public void forget(long runId) { stopped.remove(runId); }

    public static class StoppedException extends RuntimeException {
        public StoppedException() { super("Stopped by a person before it finished."); }
    }
}

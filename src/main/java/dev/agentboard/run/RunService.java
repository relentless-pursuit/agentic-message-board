package dev.agentboard.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The only way a run gets created: by the scheduler, an event, a webhook or "Run now".
 * Every path goes through the same duplicate and overload checks.
 * Screenshot source; excluded until its repository and worker runtime are integrated.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final int MAX_QUEUED_PER_AGENT = 10;

    private final RunRepository runs;
    private final JobQueue queue;

    public RunService(RunRepository runs, JobQueue queue) {
        this.runs = runs;
        this.queue = queue;
    }

    public TriggerResult trigger(String agent, String kind, String key, String detail, int depth, String payload) {
        Optional<Long> active = runs.activeRun(agent);
        boolean oneAtATime = kind.equals("schedule") || kind.equals("manual");
        if (oneAtATime && active.isPresent()) {
            return TriggerResult.busy(active.get());
        }
        if (!oneAtATime && runs.countQueued(agent) >= MAX_QUEUED_PER_AGENT) {
            log.warn("Dropped {} trigger for {}: {} runs already waiting", kind, agent, MAX_QUEUED_PER_AGENT);
            return TriggerResult.dropped();
        }
        Optional<Long> created = runs.create(agent, kind, key, detail, depth, payload);
        if (created.isEmpty()) {
            return TriggerResult.duplicate();
        }
        queue.submit(created.get());
        return TriggerResult.queued(created.get());
    }

    public record TriggerResult(String outcome, Long runId) {
        static TriggerResult queued(long id) { return new TriggerResult("queued", id); }
        static TriggerResult busy(long id) { return new TriggerResult("busy", id); }
        static TriggerResult duplicate() { return new TriggerResult("duplicate", null); }
        static TriggerResult dropped() { return new TriggerResult("dropped", null); }

        public boolean isQueued() {
            return outcome.equals("queued");
        }
    }
}

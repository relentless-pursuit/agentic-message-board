package dev.agentboard.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stage 2: the runs table is the queue. Any number of worker processes poll it; the
 * queued-to-running update in RunRepository.claim makes sure only one of them wins.
 * Screenshot source; excluded until the database-backed runtime is integrated.
 */
@Component
@ConditionalOnProperty(name = "board.queue", havingValue = "database")
public class DatabaseJobQueue implements JobQueue {

    private final RunRepository runs;

    public DatabaseJobQueue(RunRepository runs) {
        this.runs = runs;
    }

    @Override
    public void submit(long runId) {
        // Nothing to do: the run row is already marked queued.
    }

    @Override
    public Long poll(Duration wait) throws InterruptedException {
        List<Long> ids = new ArrayList<>(runs.queuedIds(10));
        if (ids.isEmpty()) {
            Thread.sleep(wait.toMillis());
            return null;
        }
        // Workers start at different places in the list so they rarely race for the same run.
        Collections.shuffle(ids);
        return ids.get(0);
    }

    @Override
    public String kind() {
        return "database";
    }
}

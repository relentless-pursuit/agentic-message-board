package dev.agentboard.run;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Stage 1: a plain Java queue. Fast and simple, but only visible inside this one process. */
@Component
@ConditionalOnProperty(name = "board.queue", havingValue = "memory", matchIfMissing = true)
public class InMemoryJobQueue implements JobQueue {

    private final LinkedBlockingQueue<Long> queue = new LinkedBlockingQueue<>();

    @Override
    public void submit(long runId) {
        queue.offer(runId);
    }

    @Override
    public Long poll(Duration wait) throws InterruptedException {
        return queue.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public String kind() {
        return "memory";
    }
}

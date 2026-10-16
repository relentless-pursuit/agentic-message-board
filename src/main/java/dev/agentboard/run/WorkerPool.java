package dev.agentboard.run;

import dev.agentboard.config.BoardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagram box "Worker pool": N threads, like Jenkins executors. Each one takes the next run from
 * the queue and executes it. More agents? Raise board.workers.threads or start more worker processes.
 * Screenshot source; excluded until AgentRunner and database-backed runtime are integrated.
 */
@Component
@ConditionalOnExpression("'${board.role:all}' != 'web'")
public class WorkerPool implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final JobQueue queue;
    private final AgentRunner runner;
    private final RunRepository runs;
    private final DbLock lock;
    private final int size;
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicInteger busy = new AtomicInteger();
    private volatile boolean running;

    public WorkerPool(JobQueue queue, AgentRunner runner, RunRepository runs, DbLock lock, BoardProperties props) {
        this.queue = queue;
        this.runner = runner;
        this.runs = runs;
        this.lock = lock;
        this.size = Math.max(1, props.getWorkers().getThreads());
    }

    @Override
    public void start() {
        running = true;
        // The in-memory queue is empty after a restart; put back runs that were waiting.
        if (queue.kind().equals("memory")) {
            runs.queuedIds(10_000).forEach(queue::submit);
        }
        for (int i = 1; i <= size; i++) {
            String name = lock.owner() + "#" + i;
            Thread t = new Thread(() -> loop(name), "agent-worker-" + i);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        log.info("Started {} workers on the {} queue", size, queue.kind());
    }

    private void loop(String name) {
        while (running) {
            try {
                Long id = queue.poll(Duration.ofSeconds(1));
                if (id == null) continue;
                busy.incrementAndGet();
                try {
                    runner.execute(id, name);
                } finally {
                    busy.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                log.error("Worker {} hit an unexpected error; continuing", name, e);
            }
        }
    }

    @Override
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        for (Thread t : threads) {
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int size() {
        return size;
    }

    public int busy() {
        return busy.get();
    }
}

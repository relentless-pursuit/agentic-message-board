package dev.agentboard.run;

import java.time.Duration;

/**
 * The to-do list between "deciding" (workflows, webhooks, Run now) and "doing" (workers).
 * This is the box to swap when the board grows: in-memory for one process, the database for a
 * few processes, Kafka for many. Callers never know which one they are using.
 */
public interface JobQueue {

    void submit(long runId);

    /** The next run to try, or null if nothing arrived within wait. */
    Long poll(Duration wait) throws InterruptedException;

    String kind();
}

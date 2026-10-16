package dev.agentboard.run;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryJobQueueTest {
    @Test void deliversSubmittedRunsInOrderAndDoesNotRedeliverConsumedRuns() throws Exception {
        JobQueue queue = new InMemoryJobQueue();
        queue.submit(101);
        queue.submit(102);
        assertThat(queue.poll(Duration.ZERO)).isEqualTo(101L);
        assertThat(queue.poll(Duration.ZERO)).isEqualTo(102L);
        assertThat(queue.poll(Duration.ZERO)).isNull();
    }
}

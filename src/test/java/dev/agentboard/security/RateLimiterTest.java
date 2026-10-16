package dev.agentboard.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {
    @Test void enforcesPerKeyAllowanceWithoutChargingExhaustionChecks() {
        var limiter = new RateLimiter();
        assertThat(limiter.tryAcquire("agent-a", 2)).isTrue();
        assertThat(limiter.exhausted("agent-a", 2)).isFalse();
        assertThat(limiter.exhausted("agent-a", 2)).isFalse();
        assertThat(limiter.tryAcquire("agent-a", 2)).isTrue();
        assertThat(limiter.exhausted("agent-a", 2)).isTrue();
        assertThat(limiter.tryAcquire("agent-a", 2)).isFalse();
        assertThat(limiter.tryAcquire("agent-b", 2)).isTrue();
    }
}

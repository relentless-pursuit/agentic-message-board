package dev.agentboard.connect;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {
    @Test void opensOnlyAfterThresholdAndKeepsHostsIndependent() {
        var breaker = new CircuitBreaker(2, Duration.ofMinutes(1));
        breaker.failure("failing.example");
        assertThatCode(() -> breaker.check("failing.example")).doesNotThrowAnyException();
        breaker.failure("failing.example");
        assertThatThrownBy(() -> breaker.check("failing.example"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("2 times in a row");
        assertThatCode(() -> breaker.check("healthy.example")).doesNotThrowAnyException();
    }

    @Test void successResetsConsecutiveFailuresAndExpiredWindowAllowsRetry() {
        var breaker = new CircuitBreaker(2, Duration.ofMinutes(1));
        breaker.failure("example.org");
        breaker.success("example.org");
        breaker.failure("example.org");
        assertThatCode(() -> breaker.check("example.org")).doesNotThrowAnyException();
        breaker.failure("example.org");
        breaker.success("example.org");
        assertThatCode(() -> breaker.check("example.org")).doesNotThrowAnyException();

        var expired = new CircuitBreaker(1, Duration.ZERO);
        expired.failure("example.org");
        assertThatCode(() -> expired.check("example.org")).doesNotThrowAnyException();
    }
}

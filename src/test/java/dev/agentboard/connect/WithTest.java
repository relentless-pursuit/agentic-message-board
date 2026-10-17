package dev.agentboard.connect;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WithTest {
    @Test void lookBackPeriodsUseSecondsMinutesHoursOrDays() {
        assertThat(With.duration("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(With.duration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(With.duration("24h")).isEqualTo(Duration.ofHours(24));
        assertThat(With.duration("7d")).isEqualTo(Duration.ofDays(7));
        assertThatThrownBy(() -> With.duration("0m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> With.duration("1w")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> With.duration(null)).isInstanceOf(IllegalArgumentException.class);
    }
}

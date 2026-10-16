package dev.agentboard.schedule;

import dev.agentboard.registry.AgentDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleTest {
    @Test void parsesOnlyPositiveSupportedIntervals() {
        assertThat(Schedule.parseEvery("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(Schedule.parseEvery("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(Schedule.parseEvery("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(Schedule.parseEvery("1d")).isEqualTo(Duration.ofDays(1));
        assertThatThrownBy(() -> Schedule.parseEvery("0m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Schedule.parseEvery("1w")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void intervalUsesStableOffsetAndFirstRunHonoursSummaryDelay() {
        var every = new AgentDefinition.Trigger("5m", null, null, null, null);
        Duration offset = Schedule.offset("sample-agent", Duration.ofMinutes(5));
        assertThat(offset).isGreaterThanOrEqualTo(Duration.ZERO).isLessThan(Duration.ofMinutes(5));
        Instant boundary = Instant.ofEpochMilli(10 * Duration.ofMinutes(5).toMillis() + offset.toMillis());
        assertThat(Schedule.nextAfter("sample-agent", every, boundary, ZoneId.of("UTC")))
                .isEqualTo(boundary.plus(Duration.ofMinutes(5)));
        var daily = new AgentDefinition.Trigger(null, "09:30", null, null, null);
        Instant now = Instant.parse("2026-10-16T00:00:00Z");
        Instant first = Schedule.firstRun("sample-agent", daily, now, Duration.ofMinutes(2)).orElseThrow();
        assertThat(first).isAfterOrEqualTo(now.plus(Duration.ofMinutes(2)))
                .isBefore(now.plus(Duration.ofMinutes(2)).plusSeconds(20));
        assertThat(Schedule.firstRun("sample-agent", null, now, Duration.ZERO)).isEmpty();
    }

    @Test void dailyAndWeeklyTimesAreStrictlyAfterNowInConfiguredZone() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        Instant fridayAtTime = Instant.parse("2026-10-16T04:00:00Z");
        var daily = new AgentDefinition.Trigger(null, "09:30", null, null, null);
        var weekly = new AgentDefinition.Trigger(null, null, "Fri 09:30", null, null);
        assertThat(Schedule.nextAfter("sample-agent", daily, fridayAtTime, zone))
                .isEqualTo(Instant.parse("2026-10-17T04:00:00Z"));
        assertThat(Schedule.nextAfter("sample-agent", weekly, fridayAtTime, zone))
                .isEqualTo(Instant.parse("2026-10-23T04:00:00Z"));
    }
}

package dev.agentboard.schedule;

import dev.agentboard.registry.AgentDefinition;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure time maths for triggers, no clocks or databases, so it is easy to test.
 * "every" agents get a fixed per-agent offset so thousands of agents with the same
 * interval do not all start in the same second.
 */
public final class Schedule {

    private static final Pattern EVERY = Pattern.compile("^([1-9]\\d*)([smhd])$");
    private static final Pattern AT = Pattern.compile("^(?:(Mon|Tue|Wed|Thu|Fri|Sat|Sun) )?([01]\\d|2[0-3]):([0-5]\\d)$");

    private Schedule() {
    }

    public static boolean isTimed(AgentDefinition.Trigger t) {
        return t != null && (t.every() != null || t.daily() != null || t.weekly() != null);
    }

    public static Duration parseEvery(String text) {
        Matcher m = EVERY.matcher(text);
        if (!m.matches()) throw new IllegalArgumentException("bad interval: " + text);
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    /** A stable spread inside [0, window) derived from the agent's handle. */
    public static Duration offset(String handle, Duration window) {
        long ms = Math.max(1, window.toMillis());
        return Duration.ofMillis(Math.floorMod((long) handle.hashCode() * 2_654_435_761L, ms));
    }

    /** When a brand-new (or changed) agent should run for the first time. Empty = never by schedule. */
    public static Optional<Instant> firstRun(String handle, AgentDefinition.Trigger t, Instant now, Duration summaryDelay) {
        if (!isTimed(t)) return Optional.empty();
        Duration jitter = offset(handle, Duration.ofSeconds(20));
        Duration delay = t.every() != null ? Duration.ZERO : summaryDelay;
        return Optional.of(now.plus(delay).plus(jitter));
    }

    /** The next scheduled time strictly after now. Missed times are skipped, never replayed. */
    public static Instant nextAfter(String handle, AgentDefinition.Trigger t, Instant now, ZoneId zone) {
        if (t.every() != null) {
            Duration every = parseEvery(t.every());
            long d = every.toMillis();
            long off = offset(handle, every).toMillis();
            long k = Math.floorDiv(now.toEpochMilli() - off, d) + 1;
            return Instant.ofEpochMilli(k * d + off);
        }
        String spec = t.daily() != null ? t.daily() : t.weekly();
        Matcher m = AT.matcher(spec);
        if (!m.matches()) throw new IllegalArgumentException("bad time: " + spec);
        LocalTime at = LocalTime.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime candidate = local.with(at).withSecond(0).withNano(0);
        if (t.weekly() != null) {
            DayOfWeek day = dayOf(m.group(1));
            while (candidate.getDayOfWeek() != day || !candidate.toInstant().isAfter(now)) {
                candidate = candidate.plusDays(1).with(at);
            }
        } else {
            while (!candidate.toInstant().isAfter(now)) {
                candidate = candidate.plusDays(1).with(at);
            }
        }
        return candidate.toInstant();
    }

    private static DayOfWeek dayOf(String shortName) {
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).equalsIgnoreCase(shortName)) return d;
        }
        throw new IllegalArgumentException("bad day: " + shortName);
    }
}

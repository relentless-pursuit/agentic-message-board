package dev.agentboard.hook;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Remembers which signed webhook deliveries were already accepted, so a captured request cannot
 * be replayed to create more runs or posts. Works across several board processes (shared database).
 * Uses JdbcTemplate to match this project's Spring Boot 3.1 baseline.
 */
@Component
public class HookDeliveries {

    private final JdbcTemplate jdbc;

    public HookDeliveries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True the first time this delivery is seen for this agent; false on any repeat. */
    public boolean firstTime(String agent, String deliveryId) {
        try {
            jdbc.update("INSERT INTO hook_deliveries (agent_handle, delivery_id, received_at) VALUES (?, ?, ?)",
                    agent, deliveryId, OffsetDateTime.now(ZoneOffset.UTC));
            return true;
        } catch (DuplicateKeyException seenBefore) {
            return false;
        }
    }

    /** Signatures older than five minutes are refused anyway, so old records can go. */
    public void forgetOlderThan(Duration age) {
        jdbc.update("DELETE FROM hook_deliveries WHERE received_at < ?", OffsetDateTime.now(ZoneOffset.UTC).minus(age));
    }
}

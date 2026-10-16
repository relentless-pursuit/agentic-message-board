package dev.agentboard.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * "Who did what, when": every token use that changes something, every hook and every outbound call.
 * Uses JdbcTemplate to match this project's Spring Boot 3.1 baseline; the screenshot uses JdbcClient.
 */
@Component
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String target, String detail, String ip) {
        try {
            jdbc.update("INSERT INTO audit_log (at, actor, action, target, detail, ip) VALUES (?, ?, ?, ?, ?, ?)",
                    OffsetDateTime.now(ZoneOffset.UTC), cut(actor, 80), cut(action, 40), cut(target, 120), cut(detail, 1000), cut(ip, 64));
        } catch (RuntimeException e) {
            log.warn("Could not write audit entry {} {}: {}", action, target, e.getMessage());
        }
    }

    public void record(String actor, String action, String target, String detail) {
        record(actor, action, target, detail, null);
    }

    public List<Entry> recent(int limit) {
        return jdbc.query("SELECT * FROM audit_log ORDER BY id DESC LIMIT ?",
                (rs, n) -> new Entry(rs.getLong("id"), rs.getObject("at", OffsetDateTime.class), rs.getString("actor"),
                        rs.getString("action"), rs.getString("target"), rs.getString("detail"), rs.getString("ip")),
                Math.max(1, Math.min(limit, 500)));
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    public record Entry(long id, OffsetDateTime at, String actor, String action, String target, String detail, String ip) {
    }
}

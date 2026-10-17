package dev.agentboard.registry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.*;

/** Keeps SQL and database row mapping in one place. */
@Repository
public class AgentRepository {
    private final JdbcTemplate jdbc;
    public AgentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void upsert(AgentDefinition a, String source, String definitionJson) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = jdbc.update("""
                UPDATE agents SET name = ?, function_text = ?, owner = ?, contact = ?, visibility = ?,
                    tags = ?, schedule = ?, connectors = ?, source = ?, enabled = ?, active = TRUE,
                    definition = ?, updated_at = ? WHERE handle = ?
                """, a.name(), a.function(), a.owner(), a.contact(), a.visibility(),
                String.join(",", a.tags()), a.trigger().describe(), String.join(",", a.connectorNames()),
                source, a.enabled(), definitionJson, now, a.handle());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO agents (handle, name, function_text, owner, contact, visibility, tags,
                        schedule, connectors, source, enabled, active, definition, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?, ?)
                    """, a.handle(), a.name(), a.function(), a.owner(), a.contact(), a.visibility(),
                    String.join(",", a.tags()), a.trigger().describe(), String.join(",", a.connectorNames()),
                    source, a.enabled(), definitionJson, now, now);
        }
    }

    public void deactivate(String handle) {
        jdbc.update("UPDATE agents SET active = FALSE WHERE handle = ?", handle);
    }

    public int deactivateFileAgentsExcept(Collection<String> presentHandles) {
        List<String> stale = jdbc.queryForList("SELECT handle FROM agents WHERE source = 'file' AND active = TRUE", String.class)
                .stream().filter(h -> !presentHandles.contains(h)).toList();
        stale.forEach(h -> jdbc.update("UPDATE agents SET active = FALSE WHERE handle = ?", h));
        return stale.size();
    }

    public long count(String query, String owner) {
        Filter f = filter(query, owner);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM agents WHERE " + f.where(), Long.class, f.params().toArray());
        return total == null ? 0 : total;
    }

    public List<AgentView> find(String query, String owner, int limit, int offset) {
        Filter f = filter(query, owner);
        List<Object> params = new ArrayList<>(f.params()); params.add(limit); params.add(offset);
        return jdbc.query("SELECT * FROM agents WHERE " + f.where() + " ORDER BY name, handle LIMIT ? OFFSET ?",
                (rs, n) -> toView(rs), params.toArray());
    }

    public Optional<AgentView> findView(String handle) {
        return jdbc.query("SELECT * FROM agents WHERE handle = ? AND active = TRUE", (rs, n) -> toView(rs), handle).stream().findFirst();
    }

    public Optional<String> findDefinitionJson(String handle) {
        return jdbc.query("SELECT definition FROM agents WHERE handle = ? AND active = TRUE",
                (rs, n) -> rs.getString("definition"), handle).stream().findFirst();
    }

    /** Every active agent's definition JSON, for views that need to look at each agent's skill. */
    public List<String> findAllDefinitionJson() {
        return jdbc.query("SELECT definition FROM agents WHERE active = TRUE ORDER BY name, handle", (rs, n) -> rs.getString("definition"));
    }

    private static Filter filter(String query, String owner) {
        StringBuilder where = new StringBuilder("active = TRUE"); List<Object> params = new ArrayList<>();
        if (owner != null && !owner.isBlank()) { where.append(" AND owner = ?"); params.add(owner); }
        if (query != null && !query.isBlank()) {
            where.append(" AND (LOWER(handle) LIKE ? ESCAPE '!' OR LOWER(name) LIKE ? ESCAPE '!'"
                    + " OR LOWER(function_text) LIKE ? ESCAPE '!' OR LOWER(owner) LIKE ? ESCAPE '!'"
                    + " OR LOWER(tags) LIKE ? ESCAPE '!' OR LOWER(connectors) LIKE ? ESCAPE '!')");
            String escaped = query.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
            for (int i = 0; i < 6; i++) params.add("%" + escaped + "%");
        }
        return new Filter(where.toString(), params);
    }

    private static AgentView toView(ResultSet rs) throws SQLException {
        boolean enabled = rs.getBoolean("enabled");
        return new AgentView(rs.getString("handle"), rs.getString("name"), rs.getString("function_text"),
                rs.getString("owner"), rs.getString("contact"), rs.getString("visibility"),
                splitCsv(rs.getString("tags")), rs.getString("schedule"), splitCsv(rs.getString("connectors")),
                rs.getString("source"), enabled, enabled ? rs.getString("status") : "Disabled",
                rs.getObject("updated_at", OffsetDateTime.class), rs.getString("last_post_title"));
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    private record Filter(String where, List<Object> params) {}

    public void recordPost(String handle, long id, String title, Instant at) {
        jdbc.update("UPDATE agents SET last_post_id=?, last_post_title=?, last_post_at=? WHERE handle=?",
                id, title, at.atOffset(ZoneOffset.UTC), handle);
    }

    public void recordRunStatus(String handle, long id, String status, Instant at) {
        jdbc.update("UPDATE agents SET last_run_id=?, last_run_status=?, status=?, last_run_at=? WHERE handle=?",
                id, status, status, at.atOffset(ZoneOffset.UTC), handle);
    }

}

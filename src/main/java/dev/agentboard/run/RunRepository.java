package dev.agentboard.run;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Plain SQL for runs and their steps. Screenshot source; excluded pending JdbcClient and schema integration. */
@Repository
public class RunRepository {

    public static final List<String> ACTIVE = List.of("queued", "running");
    private static final String SELECT = "SELECT r.*, a.name AS agent_name FROM runs r LEFT JOIN agents a ON a.handle = r.agent_handle";

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates a queued run. Empty when a run with the same trigger key already exists (no duplicates). */
    public Optional<Long> create(String agent, String kind, String key, String detail, int depth, String payload) {
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            INSERT INTO runs (agent_handle, trigger_kind, trigger_key, trigger_detail, depth, event_payload, status, queued_at)
                            VALUES (?, ?, ?, ?, ?, ?, 'queued', ?)""")
                    .params(agent, kind, key, cut(detail, 200), depth, payload, now())
                    .update(keys, "id");
            return Optional.of(keys.getKey().longValue());
        } catch (DuplicateKeyException alreadyCreated) {
            return Optional.empty();
        }
    }

    public Optional<Long> activeRun(String agent) {
        return jdbc.sql("SELECT id FROM runs WHERE agent_handle = ? AND status IN ('queued', 'running') ORDER BY id LIMIT 1")
                .param(agent).query(Long.class).optional();
    }

    public long countQueued(String agent) {
        return jdbc.sql("SELECT COUNT(*) FROM runs WHERE agent_handle = ? AND status = 'queued'").param(agent).query(Long.class).single();
    }

    /** Only one worker can move a run from queued to running, even across processes. */
    /** Cancels a run no worker has claimed yet; true when it was still queued. */
    public boolean cancelQueued(long id, String why) {
        return jdbc.sql("UPDATE runs SET status = 'cancelled', finished_at = ?, error = ? WHERE id = ? AND status = 'queued'")
                .params(now(), cut(why, 2000), id).update() == 1;
    }

    public boolean claim(long id, String worker) {
        return jdbc.sql("UPDATE runs SET status = 'running', worker = ?, started_at = ? WHERE id = ? AND status = 'queued'")
                .params(worker, now(), id).update() == 1;
    }

    public void addStep(long runId, int seq, String stage, String name, String status, String detail, long durationMs) {
        jdbc.sql("""
                        INSERT INTO run_steps (run_id, seq, stage, name, status, detail, duration_ms, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(runId, seq, stage, cut(name, 60), status, cut(detail, 2000), durationMs, now())
                .update();
    }

    public void finish(long id, String status, String result, String error, String engine, String fingerprint, Long postId) {
        jdbc.sql("""
                        UPDATE runs SET status = ?, finished_at = ?, result = ?, error = ?, engine = ?, fingerprint = ?, post_id = ?
                        WHERE id = ?""")
                .params(status, now(), cut(result, 300), cut(error, 2000), engine, fingerprint, postId, id)
                .update();
    }

    public Optional<RunRecord> record(long id) {
        return jdbc.sql("SELECT id, agent_handle, trigger_kind, trigger_detail, depth, event_payload FROM runs WHERE id = ?")
                .param(id)
                .query((rs, n) -> new RunRecord(rs.getLong("id"), rs.getString("agent_handle"), rs.getString("trigger_kind"),
                        rs.getString("trigger_detail"), rs.getInt("depth"), rs.getString("event_payload")))
                .optional();
    }

    public Optional<RunView> find(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = ?").param(id).query((rs, n) -> toView(rs, steps(rs.getLong("id")))).optional();
    }

    public List<RunView> byAgent(String agent, int limit, int offset) {
        return jdbc.sql(SELECT + " WHERE r.agent_handle = ? ORDER BY r.id DESC LIMIT ? OFFSET ?")
                .params(agent, limit, offset).query((rs, n) -> toView(rs, null)).list();
    }

    public long countByAgent(String agent) {
        return jdbc.sql("SELECT COUNT(*) FROM runs WHERE agent_handle = ?").param(agent).query(Long.class).single();
    }

    public List<Long> queuedIds(int limit) {
        return jdbc.sql("SELECT id FROM runs WHERE status = 'queued' ORDER BY id LIMIT ?").param(limit).query(Long.class).list();
    }

    /** The fingerprint of the agent's last completed run, to skip OpenAI when nothing changed. */
    public Optional<Fingerprint> lastFingerprint(String agent, long beforeRunId) {
        return jdbc.sql("""
                        SELECT id, fingerprint FROM runs
                        WHERE agent_handle = ? AND id < ? AND status IN ('success', 'unstable') AND fingerprint IS NOT NULL
                        ORDER BY id DESC LIMIT 1""")
                .params(agent, beforeRunId)
                .query((rs, n) -> new Fingerprint(rs.getLong("id"), rs.getString("fingerprint")))
                .optional();
    }

    private List<RunView.Step> steps(long runId) {
        return jdbc.sql("SELECT * FROM run_steps WHERE run_id = ? ORDER BY seq").param(runId)
                .query((rs, n) -> new RunView.Step(rs.getInt("seq"), rs.getString("stage"), rs.getString("name"),
                        rs.getString("status"), rs.getString("detail"), rs.getLong("duration_ms")))
                .list();
    }

    private static RunView toView(ResultSet rs, List<RunView.Step> steps) throws SQLException {
        OffsetDateTime started = rs.getObject("started_at", OffsetDateTime.class);
        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        Long duration = started == null ? null
                : Duration.between(started, finished == null ? OffsetDateTime.now(ZoneOffset.UTC) : finished).toMillis();
        long postId = rs.getLong("post_id");
        Long post = rs.wasNull() ? null : postId;
        return new RunView(
                rs.getLong("id"),
                rs.getString("agent_handle"),
                rs.getString("agent_name"),
                rs.getString("trigger_kind"),
                rs.getString("trigger_detail"),
                rs.getInt("depth"),
                rs.getString("status"),
                rs.getString("worker"),
                rs.getObject("queued_at", OffsetDateTime.class),
                started,
                finished,
                duration,
                rs.getString("engine"),
                rs.getString("result"),
                rs.getString("error"),
                post,
                steps);
    }

    private static OffsetDateTime now() {
        return Instant.now().atOffset(ZoneOffset.UTC);
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    public record RunRecord(long id, String agentHandle, String triggerKind, String triggerDetail, int depth, String eventPayload) {
    }

    public record Fingerprint(long runId, String value) {
    }
}

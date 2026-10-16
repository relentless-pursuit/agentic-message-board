package dev.agentboard.post;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Screenshot source; excluded until JdbcClient/Jackson compatibility and posts schema are integrated. */
@Repository
public class PostRepository {

    private static final String SELECT = """
            SELECT p.*, a.name AS agent_name, r.engine AS run_engine, r.trigger_kind AS run_trigger_kind, r.trigger_detail AS run_input_label
            FROM posts p LEFT JOIN agents a ON a.handle = p.agent_handle LEFT JOIN runs r ON r.id = p.run_id""";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PostRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public long insert(String agent, Long runId, PostDraft d, String source, int depth, Instant at) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO posts (agent_handle, run_id, reply_to, type, severity, title, body, tags, links, source, depth, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(agent, runId, d.replyTo(), d.type(), d.severity(), d.title(), d.body(), String.join(",", d.tags()),
                        d.links().isEmpty() ? null : json.writeValueAsString(d.links()), source, depth, at.atOffset(ZoneOffset.UTC))
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public Optional<PostView> find(long id) {
        return jdbc.sql(SELECT + " WHERE p.id = ?").param(id).query((rs, n) -> toView(rs)).optional();
    }

    public List<PostView> replies(long postId) {
        return jdbc.sql(SELECT + " WHERE p.reply_to = ? AND p.archived_at IS NULL ORDER BY p.id").param(postId).query((rs, n) -> toView(rs)).list();
    }

    public List<PostView> since(Instant since, int limit) {
        return jdbc.sql(SELECT + " WHERE p.archived_at IS NULL AND p.created_at >= ? ORDER BY p.id DESC LIMIT ?")
                .params(since.atOffset(ZoneOffset.UTC), limit).query((rs, n) -> toView(rs)).list();
    }

    public long count(PostQuery q) {
        Filter f = filter(q);
        return jdbc.sql("SELECT COUNT(*) FROM posts p WHERE " + f.where()).params(f.params()).query(Long.class).single();
    }

    public List<PostView> search(PostQuery q, int limit, int offset) {
        Filter f = filter(q);
        List<Object> params = new ArrayList<>(f.params());
        params.add(limit);
        params.add(offset);
        return jdbc.sql(SELECT + " WHERE " + f.where() + " ORDER BY p.id DESC LIMIT ? OFFSET ?")
                .params(params).query((rs, n) -> toView(rs)).list();
    }

    /** Counts per agent since a time, for the KPI agent. */
    public List<AgentStats> statsSince(Instant since) {
        return jdbc.sql("""
                        SELECT agent_handle, COUNT(*) AS posts,
                               SUM(CASE WHEN type = 'alert' THEN 1 ELSE 0 END) AS alerts
                        FROM posts WHERE archived_at IS NULL AND created_at >= ? GROUP BY agent_handle ORDER BY posts DESC""")
                .param(since.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new AgentStats(rs.getString("agent_handle"), rs.getLong("posts"), rs.getLong("alerts")))
                .list();
    }

    private static Filter filter(PostQuery q) {
        StringBuilder where = new StringBuilder("p.archived_at IS NULL");
        List<Object> params = new ArrayList<>();
        if (notBlank(q.agent())) {
            where.append(" AND p.agent_handle = ?");
            params.add(q.agent().trim());
        }
        if (notBlank(q.type())) {
            where.append(" AND p.type = ?");
            params.add(q.type().trim().toLowerCase());
        }
        if (notBlank(q.tag())) {
            where.append(" AND (',' || p.tags || ',') LIKE ? ESCAPE '\\'");
            params.add("%," + escapeLike(q.tag().trim().toLowerCase()) + ",%");
        }
        if (q.runId() != null) {
            where.append(" AND p.run_id = ?");
            params.add(q.runId());
        }
        if (!q.includeReplies()) {
            where.append(" AND p.reply_to IS NULL");
        }
        if (notBlank(q.text())) {
            for (String word : q.text().trim().toLowerCase().split("\\s+")) {
                String like = "%" + escapeLike(word) + "%";
                where.append(" AND (LOWER(p.title) LIKE ? ESCAPE '\\' OR LOWER(p.body) LIKE ? ESCAPE '\\'"
                        + " OR LOWER(p.tags) LIKE ? ESCAPE '\\' OR LOWER(p.agent_handle) LIKE ? ESCAPE '\\')");
                for (int i = 0; i < 4; i++) params.add(like);
            }
        }
        return new Filter(where.toString(), params);
    }

    private PostView toView(ResultSet rs) throws SQLException {
        long runId = rs.getLong("run_id");
        Long run = rs.wasNull() ? null : runId;
        long replyTo = rs.getLong("reply_to");
        Long reply = rs.wasNull() ? null : replyTo;
        String linksJson = rs.getString("links");
        List<PostView.Link> links = linksJson == null ? List.of() : json.readValue(linksJson, new TypeReference<List<PostView.Link>>() { });
        String tags = rs.getString("tags");
        return new PostView(
                rs.getLong("id"),
                rs.getString("agent_handle"),
                rs.getString("agent_name"),
                run,
                reply,
                rs.getString("type"),
                rs.getString("severity"),
                rs.getString("title"),
                rs.getString("body"),
                tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(",")),
                links,
                rs.getString("source"),
                rs.getInt("depth"),
                rs.getObject("created_at", OffsetDateTime.class),rs.getString("run_engine"),rs.getString("run_trigger_kind"),rs.getString("run_input_label"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record Filter(String where, List<Object> params) {
    }

    public record PostQuery(String text, String agent, String tag, String type, Long runId, boolean includeReplies) {
    }

    public record AgentStats(String agent, long posts, long alerts) {
    }
}

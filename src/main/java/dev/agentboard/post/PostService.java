package dev.agentboard.post;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.event.PostCreatedEvent;
import dev.agentboard.registry.AgentRepository;
import dev.agentboard.rules.Condition;
import dev.agentboard.security.RateLimiter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one way to create a post. Whoever writes (runner, external agent, webhook), the same
 * limits apply: size, allowed values, link schemes and a per-agent rate limit.
 * Screenshot source assembled across batches; excluded pending dependencies and registry API alignment.
 */
@Service
public class PostService {

    public static final Set<String> TYPES = Set.of("update", "alert", "result", "digest", "question", "reply");
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 4000;

    private final PostRepository posts;
    private final AgentRepository agents;
    private final ApplicationEventPublisher events;
    private final RateLimiter rateLimiter;
    private final BoardProperties props;

    public PostService(PostRepository posts, AgentRepository agents, ApplicationEventPublisher events,
                       RateLimiter rateLimiter, BoardProperties props) {
        this.posts = posts;
        this.agents = agents;
        this.events = events;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    public PostView create(String agentHandle, Long runId, PostDraft raw, String source, int depth) {
        if (!rateLimiter.tryAcquire("posts:" + agentHandle, props.getLimits().getPostsPerMinute())) {
            throw new PostRejectedException(429, "too many posts from " + agentHandle + "; try again in a minute");
        }
        PostDraft draft = clean(raw);
        if (draft.replyTo() != null && posts.find(draft.replyTo()).isEmpty()) {
            throw new PostRejectedException(400, "replyTo: post " + draft.replyTo() + " does not exist");
        }
        Instant now = Instant.now();
        long id = posts.insert(agentHandle, runId, draft, source, depth, now);
        if (draft.replyTo() == null) {
            agents.recordPost(agentHandle, id, draft.title(), now);
        }
        PostView view = posts.find(id).orElseThrow();
        events.publishEvent(new PostCreatedEvent(view));
        return view;
    }

    static PostDraft clean(PostDraft d) {
        if (d == null) throw new PostRejectedException(400, "a post needs a title and a body");
        String title = text(d.title(), MAX_TITLE);
        String body = text(d.body(), MAX_BODY);
        if (title.isEmpty()) throw new PostRejectedException(400, "title is required");
        if (body.isEmpty()) body = title;
        String type = d.replyTo() != null ? "reply" : lower(d.type(), "update");

        if (!TYPES.contains(type)) {
            throw new PostRejectedException(400, "type must be one of " + TYPES);
        }
        String severity = lower(d.severity(), "low");
        if (!Condition.SEVERITIES.contains(severity)) {
            throw new PostRejectedException(400, "severity must be one of " + Condition.SEVERITIES);
        }
        List<String> tags = d.tags() == null ? List.of() : d.tags().stream()
                .map(t -> t == null ? "" : t.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", ""))
                .filter(t -> !t.isEmpty() && t.length() <= 30)
                .distinct().limit(8).toList();
        List<PostView.Link> links = d.links() == null ? List.of() : d.links().stream()
                .filter(l -> l != null && l.url() != null && l.url().matches("(?i)https?://[^\\s]{1,500}"))
                .map(l -> new PostView.Link(text(l.label() == null ? l.url() : l.label(), 80), l.url().trim()))
                .limit(5).toList();
        return new PostDraft(type, severity, title, body, tags, links, d.replyTo());
    }

    /** Removes control characters (except new lines) and trims to a maximum length. */
    private static String text(String s, int max) {
        if (s == null) return "";
        String cleaned = s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return cleaned.length() > max ? cleaned.substring(0, max - 1) + "…" : cleaned;
    }

    private static String lower(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim().toLowerCase(Locale.ROOT);
    }

    public static class PostRejectedException extends RuntimeException {
        private final int status;

        public PostRejectedException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}

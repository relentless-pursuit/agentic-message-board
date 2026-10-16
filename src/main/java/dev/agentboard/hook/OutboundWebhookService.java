package dev.agentboard.hook;

import dev.agentboard.audit.AuditLog;
import dev.agentboard.config.BoardProperties;
import dev.agentboard.event.PostCreatedEvent;
import dev.agentboard.post.PostView;
import dev.agentboard.rules.Condition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Diagram box "Webhook dispatcher": sends board news to other systems (Teams, Slack, any URL).
 * Targets are named in configuration, every call is signed when a signing secret is set.
 * Failed calls are retried, and every attempt is written to the audit log.
 * Screenshot source; excluded pending event/post/rule dependencies and Jackson version alignment.
 */
@Service
public class OutboundWebhookService {

    private static final Logger log = LoggerFactory.getLogger(OutboundWebhookService.class);
    private static final long[] BACKOFF_MS = {0, 1000, 3000};

    private final BoardProperties props;
    private final ObjectMapper json;
    private final AuditLog audit;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService background = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "webhook-sender");
        t.setDaemon(true);
        return t;
    });

    public OutboundWebhookService(BoardProperties props, ObjectMapper json, AuditLog audit) {
        this.props = props;
        this.json = json;
        this.audit = audit;
    }

    public Delivery send(String target, Map<String, Object> payload, String actor) {
        String url = props.getWebhooks().getTargets().get(target);
        if (url == null || url.isBlank()) {
            return new Delivery("not-configured", "target " + target + " is not configured (set BOARD_WEBHOOKS_TARGETS_"
                    + target.toUpperCase(Locale.ROOT).replace('-', '_') + ")");
        }
        URI uri = URI.create(url.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"localhost".equalsIgnoreCase(uri.getHost()) && !"127.0.0.1".equals(uri.getHost())) {
            return new Delivery("failed", "target " + target + " must use https");
        }
        String body = json.writeValueAsString(payload);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String deliveryId = UUID.randomUUID().toString();
        String lastError = "";
        for (int attempt = 0; attempt < BACKOFF_MS.length; attempt++) {
            try {
                if (BACKOFF_MS[attempt] > 0) Thread.sleep(BACKOFF_MS[attempt]);
                HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "agent-board/0.1")
                        .header(Hmac.DELIVERY_HEADER, deliveryId)
                        .header(Hmac.TIMESTAMP_HEADER, timestamp)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                String secret = props.getWebhooks().getSigningSecret();
                if (secret != null && !secret.isBlank()) {
                    req.header(Hmac.SIGNATURE_HEADER, Hmac.sign(secret, timestamp, body));
                }
                int status = client.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status / 100 == 2) {
                    audit.record(actor, "webhook.sent", target, "HTTP " + status + ", attempt " + (attempt + 1) + ", delivery " + deliveryId);
                    return new Delivery("sent", "HTTP " + status + " after " + (attempt + 1) + " attempt" + (attempt == 0 ? "" : "s"));
                }
                lastError = "HTTP " + status;
                if (status / 100 == 4 && status != 429) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "interrupted";
                break;
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            }
        }
        audit.record(actor, "webhook.failed", target, lastError + ", delivery " + deliveryId);
        return new Delivery("failed", lastError);
    }

    /** Board-wide rules from configuration: e.g. send every alert to Teams, whichever agent wrote it. */
    @EventListener
    public void onPost(PostCreatedEvent event) {
        List<BoardProperties.Webhooks.Subscription> subs = props.getWebhooks().getSubscriptions();
        if (subs.isEmpty()) return;
        PostView p = event.post();
        Map<String, Object> facts = Map.of("severity", p.severity(), "type", p.type(), "tags", p.tags(), "agent", p.agentHandle());
        for (BoardProperties.Webhooks.Subscription s : subs) {
            try {
                if (s.getTarget() == null || !Condition.parse(s.getWhen()).test(facts)) continue;
            } catch (IllegalArgumentException badRule) {
                log.warn("Ignoring webhook subscription to {}: {}", s.getTarget(), badRule.getMessage());
                continue;
            }
            Map<String, Object> payload = payload(p.agentHandle(), p.agentName(), p.id(), p.type(), p.severity(), p.title(), p.body(), p.tags(), postUrl(p.id()));
            background.submit(() -> send(s.getTarget(), payload, "subscription"));
        }
    }

    public String postUrl(Long postId) {
        String base = props.getPublicUrl().replaceAll("/+$", "");
        return postId == null ? base : base + "/updates.html?post=" + postId;
    }

    /** One JSON shape for every target. "text" makes it readable in Teams and Slack as-is. */
    public static Map<String, Object> payload(String agent, String agentName, Long postId, String type, String severity,
                                               String title, String body, List<String> tags, String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "[Agent Board] " + agentName + " — " + title + "\n\n" + body + "\n\n" + url);
        m.put("agent", agent);
        m.put("postId", postId);
        m.put("type", type);
        m.put("severity", severity);
        m.put("title", title);
        m.put("body", body);
        m.put("tags", tags);
        m.put("url", url);
        return m;
    }

    public record Delivery(String outcome, String detail) {
    }
}

package dev.agentboard.hook;

import dev.agentboard.audit.AuditLog;
import dev.agentboard.config.BoardProperties;
import dev.agentboard.config.SecretResolver;
import dev.agentboard.post.PostDraft;
import dev.agentboard.post.PostService;
import dev.agentboard.post.PostView;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.registry.AgentRegistry;
import dev.agentboard.run.RunService;
import dev.agentboard.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Diagram box "Inbound hooks": another system tells an agent something happened.
 * The call must be signed with the agent's secret.
 * If its trigger is on_event: hook.received, the payload starts a reasoning run;
 * otherwise the payload is posted as-is.
 * Screenshot source; excluded pending run/post/security APIs and Jackson version alignment.
 */
@RestController
public class InboundHookController {

    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private final AgentRegistry registry;
    private final SecretResolver secrets;
    private final RunService runs;
    private final PostService posts;
    private final RateLimiter rateLimiter;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final BoardProperties props;
    private final HookDeliveries deliveries;

    public InboundHookController(AgentRegistry registry, SecretResolver secrets, RunService runs, PostService posts,
                                 RateLimiter rateLimiter, AuditLog audit, ObjectMapper json, BoardProperties props,
                                 HookDeliveries deliveries) {
        this.registry = registry;
        this.secrets = secrets;
        this.runs = runs;
        this.posts = posts;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.json = json;
        this.props = props;
        this.deliveries = deliveries;
    }

    @PostMapping("/api/hooks/{handle}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String handle,
            @RequestHeader(value = Hmac.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = Hmac.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(value = Hmac.DELIVERY_HEADER, required = false) String delivery,
            @RequestBody(required = false) String body,
            HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        int failureLimit = props.getLimits().getHookFailuresPerMinute();
        if (rateLimiter.exhausted("hook-failures:" + ip, failureLimit)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "too many rejected hook calls from this address; wait a minute");
        }
        Optional<AgentDefinition> agent = registry.definition(handle);
        if (agent.isEmpty() || agent.get().inboundSecret() == null) {
            rateLimiter.tryAcquire("hook-failures:" + ip, failureLimit);
            audit.record("hook:" + handle, "hook.rejected", handle, "no such hook", ip);
            return error(HttpStatus.NOT_FOUND, "no hook for this agent");
        }
        Optional<String> secret = secrets.resolve(agent.get().inboundSecret());
        if (secret.isEmpty()) {
            audit.record("hook:" + handle, "hook.rejected", handle, "secret " + agent.get().inboundSecret() + " not set on the server", ip);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "this hook is not configured on the server yet");
        }
        String payload = body == null ? "" : body;
        String problem = checkSignature(secret.get(), timestamp, signature, payload);
        if (problem != null) {
            // Failed attempts count against the caller's address, never against the agent's own allowance.
            rateLimiter.tryAcquire("hook-failures:" + ip, failureLimit);
            audit.record("hook:" + handle, "hook.rejected", handle, problem, ip);
            return error(HttpStatus.UNAUTHORIZED, problem);
        }
        if (!Boolean.TRUE.equals(agent.get().enabled())) {
            audit.record("hook:" + handle, "hook.rejected", handle, "agent is disabled", ip);
            return error(HttpStatus.CONFLICT, "this agent is disabled");
        }
        if (!rateLimiter.tryAcquire("hook:" + handle, props.getLimits().getHookRequestsPerMinute())) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "too many hook calls for this agent");
        }
        Map<String, Object> data;
        try {
            Object parsed = json.readValue(payload, Object.class);
            if (!(parsed instanceof Map<?, ?>)) return error(HttpStatus.BAD_REQUEST, "the body must be a JSON object");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            data = m;
        } catch (RuntimeException e) {
            return error(HttpStatus.BAD_REQUEST, "the body is not valid JSON");
        }
        // The replay key comes only from signed data. The optional delivery header is kept for the log.
        String deliveryId = Hmac.sha256(timestamp.trim() + "." + payload).substring(0, 32);
        String note = delivery != null && delivery.matches("[A-Za-z0-9._-]{1,80}") ? " (sender id " + delivery + ")" : "";
        if (!deliveries.firstTime(handle, deliveryId)) {
            audit.record("hook:" + handle, "hook.duplicate", handle, "delivery " + deliveryId + note, ip);
            return ResponseEntity.ok(Map.of("status", "duplicate", "note", "this delivery was already received"));
        }

        if ("hook.received".equals(agent.get().eventName())) {
            Map<String, Object> event = new LinkedHashMap<>(data);
            event.put("event", "hook.received");
            event.put("id", deliveryId);
            RunService.TriggerResult r = runs.trigger(handle, "hook", "hook:" + deliveryId, "webhook delivery " + deliveryId + note, 0, json.writeValueAsString(event));
            audit.record("hook:" + handle, "hook.accepted", handle, "delivery " + deliveryId + note + " -> " + r.outcome(), ip);
            return ResponseEntity.accepted().body(Map.of("status", r.outcome(), "runId", r.runId() == null ? -1 : r.runId()));
        }

        PostView post = posts.create(handle, null, toDraft(data), "hook", 0);
        audit.record("hook:" + handle, "hook.accepted", handle, "delivery " + deliveryId + note + " -> post " + post.id(), ip);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "posted", "postId", post.id()));
    }

    static String checkSignature(String secret, String timestamp, String signature, String body) {
        if (timestamp == null || signature == null) {
            return "missing " + Hmac.TIMESTAMP_HEADER + " or " + Hmac.SIGNATURE_HEADER + " header";
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return "timestamp must be seconds since 1970";
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - ts) > MAX_CLOCK_SKEW_SECONDS) {
            return "timestamp too old or in the future (more than 5 minutes off)";
        }
        return Hmac.verify(secret, timestamp.trim(), body, signature) ? null : "signature does not match";
    }

    private static PostDraft toDraft(Map<String, Object> data) {
        List<String> tags = new ArrayList<>();
        if (data.get("tags") instanceof List<?> list) list.forEach(t -> tags.add(String.valueOf(t)));
        return new PostDraft(str(data.get("type")), str(data.get("severity")), str(data.get("title")), str(data.get("body")), tags, List.of(), null);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

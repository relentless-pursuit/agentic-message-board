package dev.agentboard.post;

import java.time.OffsetDateTime;
import java.util.List;

public record PostView(
        long id,
        String agentHandle,
        String agentName,
        Long runId,
        Long replyTo,
        String type,
        String severity,
        String title,
        String body,
        List<String> tags,
        List<Link> links,
        String source,
        int depth,
        OffsetDateTime createdAt,
        String engine,
        String triggerKind,
        String inputLabel) {

    public record Link(String label, String url) {
    }
}

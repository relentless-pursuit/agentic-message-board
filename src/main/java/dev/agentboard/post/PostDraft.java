package dev.agentboard.post;

import java.util.List;

/**
 * What an agent wants to say. Used by the runner, by external agents (POST /api/posts) and by
 * signed webhooks, so every source goes through the same checks.
 */
public record PostDraft(
        String type,
        String severity,
        String title,
        String body,
        List<String> tags,
        List<PostView.Link> links,
        Long replyTo) {
}

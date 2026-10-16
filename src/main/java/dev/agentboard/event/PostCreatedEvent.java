package dev.agentboard.event;

import dev.agentboard.post.PostView;

/** A post was saved. Agents with on_event: post.created and board-wide webhooks react to it. */
public record PostCreatedEvent(PostView post) {
}

package dev.agentboard.act;

import java.util.Map;

/** Contract reconstructed from the supplied PostAction, ReplyAction and WebhookAction implementations. */
public interface Action {
    String name();

    ActionResult execute(Map<String, Object> with, ActContext ctx);
}

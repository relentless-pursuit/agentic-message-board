package dev.agentboard.registry;

import java.time.OffsetDateTime;
import java.util.List;

/** What the API and the Jenkins-style table show for one agent. */
public record AgentView(
        String handle,
        String name,
        String function,
        String owner,
        String contact,
        String visibility,
        List<String> tags,
        String schedule,
        List<String> connectors,
        String source,
        boolean enabled,
        String status,
        OffsetDateTime updatedAt,
        String lastPostTitle) {
}

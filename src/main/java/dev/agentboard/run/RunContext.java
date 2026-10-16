package dev.agentboard.run;

import dev.agentboard.registry.AgentDefinition;

import java.util.Map;

/** Everything a connector, the reasoner or an action may need to know about the current run. */
public record RunContext(
        long runId,
        AgentDefinition agent,
        String triggerKind,
        String triggerDetail,
        int depth,
        Map<String, Object> event) {

    public boolean isEventDriven() {
        return !event.isEmpty();
    }
}

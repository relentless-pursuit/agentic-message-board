package dev.agentboard.event;

import dev.agentboard.registry.AgentView;

/** A new agent joined the board (for example through POST /api/agents). */
public record AgentRegisteredEvent(AgentView agent) {
}

package dev.agentboard.event;

/** A signed webhook arrived for one agent. Its payload becomes the agent's observation. */
public record HookReceivedEvent(String agentHandle, String deliveryId, String payloadJson) {
}

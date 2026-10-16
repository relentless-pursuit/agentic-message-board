package dev.agentboard.security;

import java.util.Set;

/**
 * Who is calling. agentHandle is set for an agent's own token, so it can only post as itself.
 * Scopes: "post" (share updates as that agent) and "admin" (register agents, run now, tokens, audit).
 */
public record BoardPrincipal(long tokenId, String name, String agentHandle, Set<String> scopes) {

    public boolean has(String scope) {
        return scopes.contains(scope);
    }
}

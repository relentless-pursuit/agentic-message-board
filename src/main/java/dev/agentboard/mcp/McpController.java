package dev.agentboard.mcp;

import dev.agentboard.post.PostDraft;
import dev.agentboard.post.PostRepository;
import dev.agentboard.post.PostService;
import dev.agentboard.post.PostView;
import dev.agentboard.registry.AgentRegistry;
import dev.agentboard.registry.AgentView;
import dev.agentboard.security.BoardPrincipal;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diagram box "MCP endpoint": lets AI assistants (Copilot, OpenAI) use the board as a tool over
 * MCP's HTTP transport. Tools: list_agents and search_board (open), post_update (needs an agent
 * token with the post scope, sent as "Authorization: Bearer ...").
 * Screenshot source; excluded pending post/security dependencies and Jackson version alignment.
 */
@RestController
public class McpController {

    private static final String PROTOCOL = "2025-06-18";

    private final AgentRegistry registry;
    private final PostRepository posts;
    private final PostService postService;
    private final ObjectMapper json;

    public McpController(AgentRegistry registry, PostRepository posts, PostService postService, ObjectMapper json) {
        this.registry = registry;
        this.posts = posts;
        this.postService = postService;
        this.json = json;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> handle(@RequestBody Map<String, Object> request) {
        Object id = request.get("id");
        String method = String.valueOf(request.get("method"));
        if (id == null) {
            return ResponseEntity.accepted().build();
        }
        try {
            Object result = switch (method) {
                case "initialize" -> Map.of(
                        "protocolVersion", String.valueOf(params(request).getOrDefault("protocolVersion", PROTOCOL)),
                        "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "agent-board", "version", "0.1.0"));
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> call(params(request));
                default -> null;
            };
            if (result == null) {
                return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601, "message", "unknown method " + method)));
            }
            return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result", result));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result",
                    Map.of("isError", true, "content", List.of(text("Error: " + e.getMessage())))));
        }
    }

    private Map<String, Object> call(Map<String, Object> params) {
        String name = String.valueOf(params.get("name"));
        Map<String, Object> args = asMap(params.get("arguments"));
        Object data = switch (name) {
            case "list_agents" -> registry.list(str(args.get("query")), str(args.get("owner")), 100, 0).items().stream()
                    .map(this::agentSummary).toList();
            case "search_board" -> posts.search(new PostRepository.PostQuery(str(args.get("query")), str(args.get("agent")),
                    str(args.get("tag")), str(args.get("type")), null, false), 20, 0);
            case "post_update" -> postUpdate(args);
            default -> throw new IllegalArgumentException("unknown tool " + name);
        };
        return Map.of("content", List.of(text(json.writeValueAsString(data))));
    }

    private PostView postUpdate(Map<String, Object> args) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof BoardPrincipal p) || p.agentHandle() == null || !p.has("post")) {
            throw new IllegalArgumentException("post_update needs an agent token with the post scope (Authorization: Bearer ...)");
        }
        List<String> tags = new ArrayList<>();
        if (args.get("tags") instanceof List<?> l) l.forEach(t -> tags.add(String.valueOf(t)));
        return postService.create(p.agentHandle(), null, new PostDraft(str(args.get("type")), str(args.get("severity")),
                str(args.get("title")), str(args.get("body")), tags, List.of(), null), "mcp", 0);
    }

    private Map<String, Object> agentSummary(AgentView a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handle", a.handle());
        m.put("name", a.name());
        m.put("function", a.function());
        m.put("owner", a.owner());
        m.put("status", a.status());
        m.put("lastUpdate", a.lastPostTitle());
        return m;
    }

    private static List<Map<String, Object>> tools() {
        Map<String, Object> str = Map.of("type", "string");
        return List.of(
                tool("list_agents", "List agents on the board, optionally filtered by text or owner.",
                        Map.of("query", str, "owner", str), List.of()),
                tool("search_board", "Search agents' updates by text, agent handle, tag or type (update, alert, result, digest).",
                        Map.of("query", str, "agent", str, "tag", str, "type", str), List.of()),
                tool("post_update", "Share an update on the board as the agent that owns the token.",
                        Map.of("title", str, "body", str, "type", str, "severity", str, "tags", Map.of("type", "array", "items", str)),
                        List.of("title", "body")));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> props, List<String> required) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "properties", props, "required", required));
    }

    private static Map<String, Object> text(String t) {
        return Map.of("type", "text", "text", t);
    }

    private static Map<String, Object> params(Map<String, Object> request) {
        return asMap(request.get("params"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

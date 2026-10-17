package dev.agentboard.web;

import dev.agentboard.mcp.client.McpConnector;
import dev.agentboard.mcp.client.McpConnectorService;
import dev.agentboard.workflow.WorkflowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** MCP connectors: add any MCP server, test it, and see the tools it offers to skills. */
@RestController
@RequestMapping("/api/mcp")
public class McpConnectorController {
    private final McpConnectorService connectors;
    private final WorkflowService workflows;

    public McpConnectorController(McpConnectorService connectors, WorkflowService workflows) {
        this.connectors = connectors;
        this.workflows = workflows;
    }

    @GetMapping("/connectors") public List<McpConnectorService.View> list() { return connectors.views(); }
    @GetMapping("/presets") public List<Map<String, Object>> presets() { return connectors.presets(); }
    public record SaveConnector(String id, String name, String description, String transport, String url,
                                String command, List<String> args, Map<String,String> headers, Map<String,String> env,
                                @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String accessToken) {
        McpConnector definition() { return new McpConnector(id, name, description, transport, url, command, args, headers, env); }
        @Override public String toString() { return "SaveConnector[id=" + id + ", accessToken=[redacted]]"; }
    }
    @PostMapping("/connectors") public McpConnectorService.View save(@RequestBody SaveConnector request) {
        return connectors.save(request.definition(), request.accessToken());
    }
    @PostMapping("/connectors/{id}/test") public McpConnectorService.View test(@PathVariable String id) { return connectors.test(id); }

    @DeleteMapping("/connectors/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        var users = workflows.skills().stream()
                .filter(s -> s.permittedTools().stream().anyMatch(t -> McpConnectorService.isMcpTool(t) && McpConnectorService.connectorOf(t).equals(id)))
                .map(WorkflowService.Skill::name).toList();
        if (!users.isEmpty()) throw new IllegalArgumentException("These skills use this connector: " + String.join(", ", users) + ". Remove its tools from them first.");
        connectors.delete(id);
        return Map.of("deleted", id);
    }
}

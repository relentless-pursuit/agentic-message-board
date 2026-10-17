package dev.agentboard.mcp.client;

import java.util.List;
import java.util.Map;

/**
 * One MCP server the board can use, described as data. Nothing in the code names a specific server:
 * GitHub, Notion or a local fetch server are all just rows like this.
 *
 * @param id        short identifier, also the prefix of its tool names (github__get_issue)
 * @param transport "http" for a hosted server reached at {@code url}, "stdio" for a local server started with {@code command}
 * @param headers   HTTP headers sent to a hosted server, for example Authorization: Bearer ${GITHUB_TOKEN}
 * @param env       environment variables for a local server, for example NOTION_TOKEN=${NOTION_TOKEN}
 *                  Values may contain ${NAME} placeholders; they are filled from private server credential storage
 *                  or the server's environment (.env) when connecting. UI passwords are never stored in this record.
 */
public record McpConnector(String id, String name, String description, String transport, String url,
                           String command, List<String> args, Map<String, String> headers, Map<String, String> env) {

    public McpConnector {
        args = args == null ? List.of() : List.copyOf(args);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        env = env == null ? Map.of() : Map.copyOf(env);
    }

    public boolean http() { return "http".equals(transport); }
}

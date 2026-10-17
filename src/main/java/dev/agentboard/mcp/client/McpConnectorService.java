package dev.agentboard.mcp.client;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.config.SecretResolver;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns connector records into live MCP clients. It connects, lists the server's tools, hands them to the model as
 * Spring AI tool callbacks, and remembers why a connector failed so the page can say so.
 * A tool is addressed as connectorId__toolName, which is unique across connectors and a valid OpenAI tool name.
 */
@Service
public class McpConnectorService implements ApplicationRunner, DisposableBean {

    public static final String SEPARATOR = "__";
    private static final Logger log = LoggerFactory.getLogger(McpConnectorService.class);
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,29}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Pattern ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Pattern HEADER_NAME = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    /** What the page shows for one tool. writes is true unless the server declares the tool read-only. */
    public record ToolInfo(String name, String qualifiedName, String title, String description, Map<String, Object> inputSchema, boolean writes) {}
    /** A connector with its live state: unchecked, connecting, connected or failed. */
    public record View(McpConnector connector, String state, String error, String server, List<ToolInfo> tools,
                       String checkedAt, List<String> missingSecrets, List<String> serverLog, boolean accessTokenStored) {}
    private record Status(String state, String error, String server, List<ToolInfo> tools, Instant checkedAt) {}

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SecretResolver secrets;
    private final BoardProperties props;
    private final ConnectorSecretStore credentials;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, Map<String, McpSchema.Tool>> tools = new ConcurrentHashMap<>();
    private final Map<String, Status> status = new ConcurrentHashMap<>();
    private final Map<String, Deque<String>> serverLogs = new ConcurrentHashMap<>();

    public McpConnectorService(JdbcTemplate db, ObjectMapper json, SecretResolver secrets, BoardProperties props, ConnectorSecretStore credentials) {
        this.db = db;
        this.json = json;
        this.secrets = secrets;
        this.props = props;
        this.credentials = credentials;
    }

    // ---- records ---------------------------------------------------------------------------------------------

    public List<McpConnector> connectors() {
        return db.query("SELECT detail FROM mcp_connectors ORDER BY id", (rs, n) -> json.readValue(rs.getString(1), McpConnector.class));
    }

    public Optional<McpConnector> connector(String id) {
        return db.query("SELECT detail FROM mcp_connectors WHERE id=?", (rs, n) -> json.readValue(rs.getString(1), McpConnector.class), id)
                .stream().findFirst();
    }

    public List<View> views() { return connectors().stream().map(this::view).toList(); }

    public View view(McpConnector c) {
        Status s = status.getOrDefault(c.id(), new Status("unchecked", null, null, List.of(), null));
        return new View(publicDefinition(c), s.state(), s.error(), s.server(), s.tools(), s.checkedAt() == null ? null : s.checkedAt().toString(),
                missingSecrets(c), List.copyOf(serverLogs.getOrDefault(c.id(), new ArrayDeque<>())), credentials.contains(c.id()));
    }

    public synchronized View save(McpConnector raw) { return save(raw, null); }

    public synchronized View save(McpConnector raw, String accessToken) {
        McpConnector c = validate(raw);
        String token = accessToken == null ? "" : accessToken.trim();
        // Older/custom forms may submit a literal bearer value. Move it to the same private store.
        if (token.isEmpty()) {
            String auth = c.headers().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
                    .map(Map.Entry::getValue).findFirst().orElse("");
            if (auth.startsWith("Bearer ") && !auth.contains("${") && !auth.contains("[redacted]")) token = auth.substring(7).trim();
        }
        if (!token.isEmpty() && (token.length() > 4096 || token.chars().anyMatch(Character::isWhitespace)))
            throw new IllegalArgumentException("Paste the access token only, without Bearer, spaces or line breaks.");
        if (!token.isEmpty() && (!c.http()))
            throw new IllegalArgumentException("Access tokens require a hosted server.");
        var previous = connector(c.id());
        if (token.isEmpty() && credentials.contains(c.id()) && previous.isPresent()
                && !Objects.equals(previous.get().url(), c.url()))
            throw new IllegalArgumentException("Enter the access token again when changing the server address.");
        if (!token.isEmpty() || (c.http() && credentials.contains(c.id()))) {
            var headers = new LinkedHashMap<>(c.headers());
            headers.keySet().removeIf(k -> k.equalsIgnoreCase("Authorization"));
            headers.put("Authorization", "Bearer " + ConnectorSecretStore.reference(c.id()));
            if (!token.isEmpty()) credentials.put(c.id(), token);
            c = new McpConnector(c.id(), c.name(), c.description(), c.transport(), c.url(), c.command(), c.args(), headers, c.env());
        }
        String detail = json.writeValueAsString(c);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (db.update("UPDATE mcp_connectors SET detail=?, updated_at=? WHERE id=?", detail, now, c.id()) == 0) {
            db.update("INSERT INTO mcp_connectors(id, detail, updated_at) VALUES(?,?,?)", c.id(), detail, now);
        }
        disconnect(c.id());
        status.put(c.id(), new Status("unchecked", null, null, List.of(), null));
        return view(c);
    }

    public synchronized void delete(String id) {
        disconnect(id);
        status.remove(id);
        serverLogs.remove(id);
        db.update("DELETE FROM mcp_connectors WHERE id=?", id);
        credentials.delete(id);
    }

    /** Ready-made connector settings for well-known public MCP servers (src/main/resources/mcp/presets.json). */
    public List<Map<String, Object>> presets() {
        try (var in = new ClassPathResource("mcp/presets.json").getInputStream()) {
            return json.readValue(in, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.warn("Could not read MCP presets: {}", e.getMessage());
            return List.of();
        }
    }

    // ---- connecting --------------------------------------------------------------------------------------------

    /** Reconnects from scratch and lists the tools: the Test button. */
    public View test(String id) {
        McpConnector c = connector(id).orElseThrow(() -> new IllegalArgumentException("Unknown connector: " + id));
        disconnect(id);
        try { client(id); } catch (RuntimeException ignored) { /* the failure is recorded in the status */ }
        return view(c);
    }

    /** A connected client, connecting first if needed. Fails with a message that says what to fix. */
    public McpSyncClient client(String id) {
        McpSyncClient existing = clients.get(id);
        if (existing != null && existing.isInitialized()) return existing;
        synchronized (this) {
            existing = clients.get(id);
            if (existing != null && existing.isInitialized()) return existing;
            McpConnector c = connector(id).orElseThrow(() -> new IllegalStateException("Connector '" + id + "' does not exist."));
            status.put(id, new Status("connecting", null, null, List.of(), Instant.now()));
            serverLogs.put(id, new ArrayDeque<>());
            McpSyncClient client = null;
            try {
                client = McpClient.sync(transport(c))
                        .requestTimeout(props.getMcp().getRequestTimeout())
                        .initializationTimeout(props.getMcp().getConnectTimeout())
                        .clientInfo(new McpSchema.Implementation("agent-board", "1.0"))
                        .build();
                var init = client.initialize();
                var found = new LinkedHashMap<String, McpSchema.Tool>();
                String cursor = null;
                do {
                    var page = cursor == null ? client.listTools() : client.listTools(cursor);
                    page.tools().forEach(t -> found.put(qualify(id, t.name()), t));
                    cursor = page.nextCursor();
                } while (cursor != null && found.size() < 500);
                clients.put(id, client);
                tools.put(id, found);
                String server = init.serverInfo() == null ? null : init.serverInfo().name() + " " + Objects.toString(init.serverInfo().version(), "");
                status.put(id, new Status("connected", null, server, found.entrySet().stream().map(e -> info(e.getKey(), e.getValue())).toList(), Instant.now()));
                log.info("MCP connector {} connected: {} tools", id, found.size());
                return client;
            } catch (Exception e) {
                if (client != null) try { client.close(); } catch (Exception ignored) { }
                String reason = redact(c, explain(e));
                status.put(id, new Status("failed", reason, null, List.of(), Instant.now()));
                log.warn("MCP connector {} failed: {}", id, reason);
                throw new IllegalStateException("Connector '" + id + "' is not available: " + reason, e);
            }
        }
    }

    private McpClientTransport transport(McpConnector c) {
        if (c.http()) {
            URI uri = URI.create(c.url());
            String base = uri.getScheme() + "://" + uri.getRawAuthority();
            String endpoint = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            Map<String, String> headers = new LinkedHashMap<>();
            c.headers().forEach((k, v) -> headers.put(k, fill(v)));
            return HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint)
                    .connectTimeout(Duration.ofSeconds(20))
                    .httpRequestCustomizer((request, method, endpointUri, body, context) -> headers.forEach(request::setHeader))
                    .build();
        }
        if (!props.getMcp().getAllowedCommands().contains(c.command())) {
            throw new IllegalStateException("The command '" + c.command() + "' is not allowed. Allowed: " + props.getMcp().getAllowedCommands() + " (board.mcp.allowed-commands).");
        }
        Map<String, String> env = new LinkedHashMap<>();
        c.env().forEach((k, v) -> env.put(k, fill(v)));
        var params = ServerParameters.builder(c.command()).args(c.args().stream().map(this::fill).toList()).env(env).build();
        var transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        Deque<String> lines = serverLogs.computeIfAbsent(c.id(), k -> new ArrayDeque<>());
        transport.setStdErrorHandler(line -> { synchronized (lines) { if (lines.size() >= 30) lines.removeFirst(); lines.addLast(redact(c, line)); } });
        return transport;
    }

    private void disconnect(String id) {
        McpSyncClient old = clients.remove(id);
        tools.remove(id);
        if (old != null) try { old.closeGracefully(); } catch (Exception e) { old.close(); }
    }

    // ---- tools for the model -------------------------------------------------------------------------------------

    public static boolean isMcpTool(String qualifiedName) { return qualifiedName != null && qualifiedName.contains(SEPARATOR); }
    public static String connectorOf(String qualifiedName) { return qualifiedName.substring(0, qualifiedName.indexOf(SEPARATOR)); }

    /** The MCP tool behind a qualified name, connecting its connector first if needed. */
    public McpSchema.Tool tool(String qualifiedName) {
        String id = connectorOf(qualifiedName);
        client(id);
        McpSchema.Tool tool = tools.getOrDefault(id, Map.of()).get(qualifiedName);
        if (tool == null) throw new IllegalStateException("Connector '" + id + "' no longer offers the tool " + qualifiedName.substring(id.length() + SEPARATOR.length()) + ". Test the connector and update the skill.");
        return tool;
    }

    /** A Spring AI tool callback the model can call; executing it calls the MCP server. */
    public ToolCallback callback(String qualifiedName) {
        return SyncMcpToolCallback.builder().mcpClient(client(connectorOf(qualifiedName))).tool(tool(qualifiedName)).prefixedToolName(qualifiedName).build();
    }

    /**
     * Calls a tool. A server that stops answering (a stuck local process, a dropped session) is reconnected; a read-only
     * tool is then retried once. A tool that can change data is not retried, so nothing happens twice.
     */
    public String call(String qualifiedName, String argumentsJson) {
        try {
            return callback(qualifiedName).call(argumentsJson);
        } catch (RuntimeException e) {
            if (!timedOut(e)) throw e;
            String id = connectorOf(qualifiedName);
            long seconds = props.getMcp().getRequestTimeout().toSeconds();
            boolean writes = info(qualifiedName, tool(qualifiedName)).writes();
            log.warn("MCP connector {} did not answer {} within {} s; reconnecting", id, qualifiedName, seconds);
            synchronized (this) { McpSyncClient old = clients.remove(id); tools.remove(id); if (old != null) try { old.close(); } catch (Exception ignored) { } }
            if (writes) throw new IllegalStateException("The " + id + " server did not answer within " + seconds + " s. The board reconnected it; this call was not retried because the tool can change data.", e);
            try {
                return callback(qualifiedName).call(argumentsJson);
            } catch (RuntimeException again) {
                if (!timedOut(again)) throw again;
                status.put(id, new Status("failed", "Stopped answering: no reply within " + seconds + " s, even after reconnecting.", null, List.of(), Instant.now()));
                throw new IllegalStateException("The " + id + " server did not answer within " + seconds + " s, even after the board reconnected it. The site may be slow or blocking automated reads.", again);
            }
        }
    }

    private static boolean timedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException) return true;
            if (Objects.toString(t.getMessage(), "").contains("Did not observe any item")) return true;
        }
        return false;
    }

    /** Checks model-written arguments against the tool's own input schema before anything is sent to the server. */
    public Optional<String> invalidArguments(String qualifiedName, Map<String, Object> arguments) {
        var schema = tool(qualifiedName).inputSchema();
        if (schema == null || schema.isEmpty()) return Optional.empty();
        var result = McpJsonDefaults.getSchemaValidator().validate(schema, arguments);
        return result.valid() ? Optional.empty() : Optional.of(Objects.toString(result.errorMessage(), "arguments do not match the tool's input schema"));
    }

    /** Every tool of every connected connector, for the skill editor. */
    public boolean knows(String qualifiedName) {
        if (!isMcpTool(qualifiedName)) return false;
        String id = connectorOf(qualifiedName);
        if (!tools.containsKey(id)) try { client(id); } catch (RuntimeException e) { return false; }
        return tools.getOrDefault(id, Map.of()).containsKey(qualifiedName);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static String qualify(String connectorId, String toolName) {
        return connectorId + SEPARATOR + toolName.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static ToolInfo info(String qualified, McpSchema.Tool t) {
        boolean readOnly = t.annotations() != null && Boolean.TRUE.equals(t.annotations().readOnlyHint());
        String title = t.title() != null ? t.title() : t.annotations() != null ? t.annotations().title() : null;
        return new ToolInfo(t.name(), qualified, title, Objects.toString(t.description(), ""), t.inputSchema(), !readOnly);
    }

    /** Names in ${NAME} placeholders that have no value in the server's environment. */
    public List<String> missingSecrets(McpConnector c) {
        var names = new LinkedHashSet<String>();
        var values = new ArrayList<String>();
        values.addAll(c.headers().values());
        values.addAll(c.env().values());
        values.addAll(c.args());
        if (c.url() != null) values.add(c.url());
        for (String v : values) {
            Matcher m = PLACEHOLDER.matcher(v == null ? "" : v);
            while (m.find()) {
                String name = m.group(1);
                if (invalidReference(name)) { names.add("invalid token placeholder — use the Access token field"); continue; }
                if (credentials.resolve(name).isEmpty() && secrets.resolve(name).isEmpty() && Objects.toString(System.getenv(name), "").isBlank()) names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private String fill(String template) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            if (invalidReference(name)) throw new IllegalStateException("Paste your PAT into the Access token field, without ${...}.");
            String value = credentials.resolve(name).orElseGet(() -> secrets.resolve(name).orElseGet(() -> System.getenv(name)));
            if (value == null || value.isBlank()) throw new IllegalStateException(name + " is not set. Add " + name + "=... to .env and restart the board.");
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean invalidReference(String name) {
        return name.length() > 64 || name.startsWith("github_pat_") || name.matches("gh[pousr]_.*");
    }

    private McpConnector publicDefinition(McpConnector c) {
        var headers = new LinkedHashMap<>(c.headers());
        headers.replaceAll((k,v) -> {
            if (k.equalsIgnoreCase("Authorization") && v.startsWith("Bearer ") && !v.contains("${")) return "Bearer [redacted]";
            return v.replaceAll("github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]+", "[redacted]");
        });
        return new McpConnector(c.id(), c.name(), c.description(), c.transport(), c.url(), c.command(), c.args(), headers, c.env());
    }

    private String redact(McpConnector c, String text) {
        String safe = text;
        try {
            var value = credentials.resolve(ConnectorSecretStore.name(c.id()));
            if (value.isPresent()) safe = safe.replace(value.get(), "[redacted]");
        } catch (RuntimeException ignored) { }
        return safe.replaceAll("github_pat_[A-Za-z0-9_]+|gh[pousr]_[A-Za-z0-9_]+", "[redacted]");
    }

    private static String explain(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = Objects.toString(root.getMessage(), root.getClass().getSimpleName());
        if (message.contains("Cannot run program")) return message + ". Install the program or choose another connector.";
        if (message.contains("Authorization error")) return "The server refused authentication. Check the access token and server permissions, then save again.";
        if (message.contains("401") || message.contains("Unauthorized")) return "The server refused the credentials (401). Enter a valid access token and save again.";
        if (message.contains("403")) return "The server refused access (403). Check the token permissions and repository access.";
        if (e instanceof IllegalStateException && e.getMessage() != null && !e.getMessage().equals(message)) return e.getMessage() + " (" + message + ")";
        return message;
    }

    private McpConnector validate(McpConnector c) {
        if (c == null) throw new IllegalArgumentException("Connector is required.");
        if (c.id() == null || !ID.matcher(c.id()).matches()) throw new IllegalArgumentException("ID must be 2–30 lowercase letters, numbers or dashes, for example notion or github.");
        if (c.name() == null || c.name().isBlank() || c.name().length() > 60) throw new IllegalArgumentException("Enter a name of up to 60 characters.");
        if (c.description() != null && c.description().length() > 300) throw new IllegalArgumentException("Keep the description under 300 characters.");
        if ("http".equals(c.transport())) {
            URI uri;
            try { uri = URI.create(Objects.toString(c.url(), "").trim()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Enter a valid server address."); }
            boolean local = "http".equals(uri.getScheme()) && List.of("localhost", "127.0.0.1").contains(uri.getHost());
            if (!("https".equals(uri.getScheme()) || local) || uri.getHost() == null) throw new IllegalArgumentException("A hosted MCP server needs an https:// address (http:// only for localhost).");
            c.headers().keySet().forEach(k -> { if (!HEADER_NAME.matcher(k).matches()) throw new IllegalArgumentException("Invalid header name: " + k); });
            return new McpConnector(c.id(), c.name().trim(), c.description(), "http", uri.toString(), null, List.of(), c.headers(), Map.of());
        }
        if ("stdio".equals(c.transport())) {
            String command = Objects.toString(c.command(), "").trim();
            if (!props.getMcp().getAllowedCommands().contains(command)) throw new IllegalArgumentException("Command must be one of " + props.getMcp().getAllowedCommands() + ". Add others to board.mcp.allowed-commands.");
            if (c.args().size() > 30 || c.args().stream().anyMatch(a -> a == null || a.length() > 400)) throw new IllegalArgumentException("Use at most 30 arguments of up to 400 characters.");
            c.env().keySet().forEach(k -> { if (!ENV_NAME.matcher(k).matches()) throw new IllegalArgumentException("Invalid environment variable name: " + k); });
            return new McpConnector(c.id(), c.name().trim(), c.description(), "stdio", null, command, c.args(), Map.of(), c.env());
        }
        throw new IllegalArgumentException("Choose how to reach the server: a hosted address (http) or a local command (stdio).");
    }

    /**
     * On a board with no connectors yet, adds the presets marked "starter" that can work here: no missing ${NAME}
     * secret and, for a local server, its command on the PATH. So a fresh board with only OPENAI_API_KEY has tools.
     */
    void addStarters() {
        if (db.queryForObject("SELECT COUNT(*) FROM mcp_connectors", Integer.class) > 0) return;
        for (var p : presets()) {
            if (!Boolean.TRUE.equals(p.get("starter"))) continue;
            var fields = new LinkedHashMap<>(p);
            fields.remove("starter");
            try {
                McpConnector c = json.convertValue(fields, McpConnector.class);
                if (!missingSecrets(c).isEmpty() || (!c.http() && !onPath(c.command()))) continue;
                save(c);
                log.info("Added starter MCP connector {}", c.id());
            } catch (RuntimeException e) {
                log.warn("Starter connector {} skipped: {}", p.get("id"), e.getMessage());
            }
        }
    }

    private static boolean onPath(String command) {
        if (command == null) return false;
        return Arrays.stream(Objects.toString(System.getenv("PATH"), "").split(java.io.File.pathSeparator))
                .filter(d -> !d.isBlank()).anyMatch(d -> java.nio.file.Files.isExecutable(java.nio.file.Path.of(d, command)));
    }

    /** Connects every saved connector in the background at start-up, so the page shows which ones work. */
    @Override
    public void run(ApplicationArguments args) {
        try { addStarters(); } catch (RuntimeException e) { log.warn("Starter connectors were not added: {}", e.getMessage()); }
        Thread t = new Thread(() -> connectors().forEach(c -> { try { client(c.id()); } catch (RuntimeException ignored) { } }), "mcp-connect");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void destroy() { new ArrayList<>(clients.keySet()).forEach(this::disconnect); }
}

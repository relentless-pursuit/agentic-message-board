package dev.agentboard.mcp.client;

import com.sun.net.httpserver.HttpServer;
import dev.agentboard.config.BoardProperties;
import dev.agentboard.config.SecretResolver;
import dev.agentboard.web.McpConnectorController;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ConnectorCredentialTest {
    @TempDir Path directory;
    ObjectMapper json = new ObjectMapper();
    BoardProperties props;
    ConnectorSecretStore secrets;
    JdbcTemplate db;
    McpConnectorService service;
    HttpServer server;

    @BeforeEach void setup() {
        props = new BoardProperties(); props.setDataDir(directory.toString());
        props.getMcp().setConnectTimeout(Duration.ofSeconds(5)); props.getMcp().setRequestTimeout(Duration.ofSeconds(5));
        secrets = new ConnectorSecretStore(props);
        db = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", ""));
        db.execute("CREATE TABLE mcp_connectors(id VARCHAR(30) PRIMARY KEY, detail TEXT, updated_at TIMESTAMP WITH TIME ZONE)");
        service = new McpConnectorService(db, json, new SecretResolver(new MockEnvironment()), props, secrets);
    }
    @AfterEach void stop() { service.destroy(); if (server != null) server.stop(0); }
    McpConnector definition(String url) { return new McpConnector("github", "GitHub", "Fixture", "http", url, null, List.of(), Map.of("Authorization", "Bearer ${GITHUB_TOKEN}"), Map.of()); }

    @Test void tokenStaysPrivatePersistsAndCanBeReplacedOrDeleted() throws Exception {
        String token = "fixture-private-token-original";
        var saved = service.save(definition("https://example.test/mcp/"), token);
        assertThat(saved.accessTokenStored()).isTrue();
        assertThat(saved.missingSecrets()).isEmpty();
        assertThat(json.writeValueAsString(saved)).doesNotContain(token).contains("BOARD_MCP_TOKEN_GITHUB");
        assertThat(db.queryForObject("SELECT detail FROM mcp_connectors", String.class)).doesNotContain(token);
        Path privateDir = directory.resolve("secrets/connectors");
        assertThat(Files.getPosixFilePermissions(privateDir)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        try (var paths = Files.list(privateDir)) {
            for (Path p : paths.toList()) {
                assertThat(Files.getPosixFilePermissions(p)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
                assertThat(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).doesNotContain(token);
            }
        }
        assertThat(new ConnectorSecretStore(props).resolve(ConnectorSecretStore.name("github"))).contains(token);
        service.save(definition("https://example.test/mcp/"), "");
        assertThat(secrets.resolve(ConnectorSecretStore.name("github"))).contains(token);
        service.save(saved.connector(), "replacement-private-token");
        assertThat(secrets.resolve(ConnectorSecretStore.name("github"))).contains("replacement-private-token");
        service.delete("github");
        assertThat(secrets.resolve(ConnectorSecretStore.name("github"))).isEmpty();
    }

    @Test void changedServerCannotReceiveAnExistingSavedToken() {
        var saved = service.save(definition("https://first.test/mcp"), "private-test-token");
        var changed = new McpConnector("github","GitHub","Fixture","http","https://other.test/mcp",null,List.of(),saved.connector().headers(),Map.of());
        assertThatThrownBy(() -> service.save(changed, "")).hasMessageContaining("Enter the access token again");
        assertThat(service.connector("github").orElseThrow().url()).isEqualTo("https://first.test/mcp");
    }

    @Test void legacyTokenPlaceholderDoesNotLeakInReturnedConfigurationOrError() {
        String token = "github_pat_ThisIsAnInventedFixtureNeverARealToken";
        var legacy = new McpConnector("github","GitHub","Fixture","http","https://example.test/mcp",null,List.of(),Map.of("Authorization","Bearer ${"+token+"}"),Map.of());
        service.save(legacy);
        var failed = service.test("github");
        assertThat(failed.state()).isEqualTo("failed");
        assertThat(failed.error()).contains("Access token field");
        assertThat(json.writeValueAsString(failed)).doesNotContain(token);
    }

    @Test void literalBearerIsMovedIntoPrivateStorageAndCorruptionFailsClosed() throws Exception {
        String token = "fixture-private-token";
        var raw = new McpConnector("github","GitHub","Fixture","http","https://example.test/mcp",null,List.of(),Map.of("Authorization","Bearer "+token),Map.of());
        var saved = service.save(raw);
        assertThat(saved.accessTokenStored()).isTrue();
        assertThat(json.writeValueAsString(saved)).doesNotContain(token);
        Path file = directory.resolve("secrets/connectors/"+ConnectorSecretStore.name("github")+".enc");
        byte[] bytes = Files.readAllBytes(file); bytes[bytes.length-1] ^= 1; Files.write(file,bytes);
        assertThatThrownBy(() -> secrets.resolve(ConnectorSecretStore.name("github")))
                .hasMessageContaining("Enter it again");
    }

    @Test void requestSerializationNeverReturnsThePassword() throws Exception {
        var request = new McpConnectorController.SaveConnector("github","GitHub","Fixture","http","https://example.test/mcp",null,List.of(),Map.of(),Map.of(),"private-test-token");
        assertThat(json.writeValueAsString(request)).doesNotContain("private-test-token");
        assertThat(request.toString()).doesNotContain("private-test-token");
        var parsed = json.readValue("{\"id\":\"github\",\"accessToken\":\"private-test-token\"}", McpConnectorController.SaveConnector.class);
        assertThat(parsed.accessToken()).isEqualTo("private-test-token");
    }

    @Test void rejectedCredentialHasAClearSafeError() throws Exception {
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp", exchange -> { exchange.sendResponseHeaders(401,-1); exchange.close(); });
        server.start();
        service.save(definition("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp"),"fixture-rejected-token");
        var failed = service.test("github");
        assertThat(failed.state()).isEqualTo("failed");
        assertThat(failed.error()).contains("refused authentication").doesNotContain("fixture-rejected-token");
        assertThat(failed.accessTokenStored()).isTrue();
    }

    @Test void saveThenTestActuallySendsTheStoredBearerAndListsToolsWithoutRestart() throws Exception {
        var authorization = new AtomicReference<String>();
        server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405,-1); exchange.close(); return; }
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            Map<?,?> request = json.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
            if (!request.containsKey("id")) { exchange.sendResponseHeaders(202,-1); exchange.close(); return; }
            Object result = "initialize".equals(request.get("method"))
                    ? Map.of("protocolVersion","2025-03-26","capabilities",Map.of("tools",Map.of()),"serverInfo",Map.of("name","credential-fixture","version","1"))
                    : Map.of("tools",List.of(Map.of("name","read_fixture","description","Reads fixture","inputSchema",Map.of("type","object","properties",Map.of()))));
            byte[] body = json.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var saved = service.save(definition("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp"),"fixture-bearer-token");
        assertThat(saved.accessTokenStored()).isTrue();
        var tested = service.test("github");
        assertThat(tested.state()).as("connection error: %s", tested.error()).isEqualTo("connected");
        assertThat(tested.tools()).extracting(McpConnectorService.ToolInfo::name).contains("read_fixture");
        assertThat(authorization.get()).isEqualTo("Bearer fixture-bearer-token");
        assertThat(json.writeValueAsString(tested)).doesNotContain("fixture-bearer-token");
    }
}

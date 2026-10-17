package dev.agentboard;

import dev.agentboard.registry.AgentRegistry;
import dev.agentboard.audit.AuditLog;
import dev.agentboard.config.BoardProperties;
import dev.agentboard.hook.HookDeliveries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:registry-tests;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc(addFilters = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistryIntegrationTest {
    private static final Path DIR = fixtures();

    @Autowired MockMvc mvc;
    @Autowired AgentRegistry registry;
    @Autowired ObjectMapper json;
    @Autowired AuditLog audit;
    @Autowired BoardProperties boardProperties;
    @Autowired HookDeliveries deliveries;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeAll void captureUiFixtures() throws Exception {
        Map<String, Object> responses = new LinkedHashMap<>();
        for (String path : new String[]{"/agents?limit=500", "/owners", "/registry/errors", "/health"}) {
            String response = mvc.perform(get("/api" + path)).andReturn().getResponse().getContentAsString();
            responses.put(path, json.readTree(response));
        }
        for (var agent : registry.list(null, null, 500, 0).items()) {
            String path = "/agents/" + agent.handle();
            responses.put(path, json.readTree(mvc.perform(get("/api" + path)).andReturn().getResponse().getContentAsString()));
        }
        Files.createDirectories(Path.of("target/ui-fixtures"));
        Files.writeString(Path.of("target/ui-fixtures/responses.json"), json.writeValueAsString(responses));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("board.agents-dir", DIR::toString);
        properties.add("board.data-dir", () -> DIR.resolve("runtime").toString());
    }

    private static Path fixtures() {
        try {
            Path directory = Files.createTempDirectory("agent-board-test-");
            try (Stream<Path> files = Files.list(Path.of("src/test/resources/agents"))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".yaml")).toList()) {
                    Files.copy(file, directory.resolve(file.getFileName()));
                }
            }
            return directory;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @Test void loadsCompanyAgentsAndSupportsTeamFilter() throws Exception {
        mvc.perform(get("/api/agents").param("owner", "platform-team"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].owner").value("platform-team"))
                .andExpect(jsonPath("$.items[0].status").value("Never run"));
    }

    @Test void searchAndPagingUseTheDatabase() throws Exception {
        mvc.perform(get("/api/agents").param("q", "compilation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].handle").value("build-monitor"));
        mvc.perform(get("/api/agents").param("limit", "2").param("offset", "2"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.offset").value(2));
    }

    @Test void searchDoesNotInterpretSqlOrWildcards() throws Exception {
        mvc.perform(get("/api/agents").param("q", "' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/agents").param("q", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test void detailsExposeConfigurationAndUnknownAgentsReturn404() throws Exception {
        mvc.perform(get("/api/agents/build-monitor"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.observe[0].connector").value("github_actions"))
                .andExpect(jsonPath("$.act[0].action").value("post"))
                .andExpect(jsonPath("$.configYaml").isString());
        mvc.perform(get("/api/agents/missing-agent")).andExpect(status().isNotFound());
    }

    @Test void registrationPersistsAFileAndRejectsDuplicateHandles() throws Exception {
        String input = """
                {"handle":"new-check","name":"New Check","function":"Checks shared engineering work.",
                 "owner":"test-team","visibility":"company","trigger":{},
                 "observe":[{"connector":"http_check","with":{"urls":["https://www.githubstatus.com"]}}],
                 "reason":"Summarize the supplied measurements.","act":[{"action":"post","with":{}}]}
                """;
        mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.agent.handle").value("new-check"));
        assertThat(DIR.resolve("new-check.yaml")).exists();
        registry.reloadFiles();
        mvc.perform(get("/api/agents/new-check")).andExpect(status().isOk());
        mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isString());
    }

    @Test void badFilesDoNotDeactivateExistingAgents() throws Exception {
        Path broken = DIR.resolve("build-monitor.yaml");
        String original = Files.readString(broken);
        try {
            Files.writeString(broken, "handle: [broken YAML");
            registry.reloadFiles();
            mvc.perform(get("/api/agents/build-monitor")).andExpect(status().isOk());
        } finally { Files.writeString(broken, original); registry.reloadFiles(); }
    }

    @Test void manualIssueAgentIsSavedWithoutCreatingARun() throws Exception {
        String input="""
                {"handle":"manual-issues","name":"Repository issues","function":"List open repository issues.","owner":"me",
                 "trigger":{},"observe":[{"connector":"github_issues","with":{"repo":"example/repository"}}],
                 "reasoningProvider":"openai","act":[{"action":"post","with":{}}]}
                """;
        mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.agent.schedule").value("manual"))
                .andExpect(jsonPath("$.reasoningProvider").value("openai"));
        registry.reloadFiles();
        mvc.perform(get("/api/agents/manual-issues")).andExpect(status().isOk()).andExpect(jsonPath("$.agent.tags").isEmpty());
        mvc.perform(get("/api/agents/manual-issues/runs")).andExpect(jsonPath("$.total").value(0));
    }

    @Test void validationRejectsNullStepsAndUnsafeHandles() throws Exception {
        String input = """
                {"handle":"../escape","name":"Bad","function":"Bad input","owner":"test-team",
                 "trigger":{},"observe":[null],"act":[null]}
                """;
        mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString());
        assertThat(DIR.resolve("../escape.yaml")).doesNotExist();
    }

    @Test void servesTheUiAndReportsExecutionHonestly() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    @Test void auditPersistsBoundedEntriesAndConfigurationIsRegistered() {
        assertThat(boardProperties.getAgentsDir()).isEqualTo(DIR.toString());
        audit.record("demo-actor", "post", "demo-target", "x".repeat(1100));
        audit.record("demo-actor", "reply", "demo-target", "latest", "127.0.0.1");
        var recent = jdbc.queryForList("SELECT detail, ip FROM audit_log ORDER BY id DESC LIMIT 2");
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).get("DETAIL")).isEqualTo("latest");
        assertThat(recent.get(0).get("IP")).isEqualTo("127.0.0.1");
        assertThat((String) recent.get(1).get("DETAIL")).hasSize(1000);
        assertThat(recent.get(1).get("IP")).isNull();
    }

    @Test void deliveriesRejectReplayPerAgentAndCleanupOnlyOldRecords() {
        assertThat(deliveries.firstTime("first-agent", "same-delivery")).isTrue();
        assertThat(deliveries.firstTime("first-agent", "same-delivery")).isFalse();
        assertThat(deliveries.firstTime("second-agent", "same-delivery")).isTrue();
        jdbc.update("UPDATE hook_deliveries SET received_at = ? WHERE agent_handle = ?",
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(2), "first-agent");
        // Each new delivery clears records older than a day, after which a stale id is accepted again.
        assertThat(deliveries.firstTime("first-agent", "same-delivery")).isTrue();
        assertThat(deliveries.firstTime("second-agent", "same-delivery")).isFalse();
    }

    @AfterAll static void cleanup() throws IOException {
        try (Stream<Path> files = Files.walk(DIR)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    @Test void newDefinitionsRejectRemovedProviderSelections() throws Exception {
        mvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON).content("{\"handle\":\"removed-provider\",\"reasoningProvider\":\"template\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void historicalFileDefinitionsNormalizeWithoutRunning() throws Exception {
        Path file=DIR.resolve("legacy-provider.yaml");
        try {
            Files.writeString(file,"{\"handle\":\"legacy-provider\",\"name\":\"Legacy reader\",\"function\":\"Review source\",\"owner\":\"me\",\"trigger\":{},\"observe\":[],\"reasoningProvider\":\"template\",\"act\":[{\"action\":\"post\",\"with\":{}}]}");
            registry.reloadFiles();
            mvc.perform(get("/api/agents/legacy-provider")).andExpect(status().isOk()).andExpect(jsonPath("$.reasoningProvider").value("openai"));
            mvc.perform(get("/api/agents/legacy-provider/runs")).andExpect(jsonPath("$.total").value(0));
        } finally {Files.deleteIfExists(file);registry.reloadFiles();}
    }
}

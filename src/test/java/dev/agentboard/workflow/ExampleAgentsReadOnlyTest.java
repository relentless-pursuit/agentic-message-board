package dev.agentboard.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** With the example team shown (start.sh), an agent created in the UI is saved to agents-dir, never to examples/. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:example-readonly;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "board.example-agents-dir=examples/agents", "board.workflows-dir=examples/workflows"})
class ExampleAgentsReadOnlyTest {

    static final Path DIR = Path.of("target/example-readonly-" + System.nanoTime());

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry p) {
        p.add("board.agents-dir", () -> DIR.resolve("agents").toString());
        p.add("board.data-dir", () -> DIR.resolve("data").toString());
    }

    @Autowired WorkflowService service;

    @Test
    void aNewAgentIsSavedOutsideExamples() throws Exception {
        long examplesBefore = yamlCount(Path.of("examples/agents"));
        String skill = service.skills().stream().filter(s -> s.operation() == null).findFirst().orElseThrow().id();

        service.createAgent(new WorkflowService.NewAgent("Saved locally", "Checks where new agents are saved", skill));

        assertThat(yamlCount(Path.of("examples/agents"))).isEqualTo(examplesBefore);
        assertThat(yamlCount(DIR.resolve("agents"))).isEqualTo(1);
        assertThat(service.agents()).hasSize(11);
    }

    private static long yamlCount(Path dir) throws Exception {
        try (Stream<Path> files = Files.list(dir)) { return files.filter(f -> f.toString().endsWith(".yaml")).count(); }
    }
}

package dev.agentboard.registry;

import dev.agentboard.config.BoardProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDefinitionPersistenceTest {
    @TempDir Path directory;

    @Test void storedAgentWithoutObserveStepsRestoresEmptyListsForExecution() {
        var json = new ObjectMapper();
        var registry = new AgentRegistry(null, null, json, directory.toString(), new BoardProperties(), null);
        var original = new AgentDefinition("text-only", "Text only", "Summarize workflow input.", "test",
                null, "company", List.of(), new AgentDefinition.Trigger(null), List.of(),
                "Summarize the supplied input.", List.of(new AgentDefinition.ActStep("post", Map.of())), true).normalized();

        String stored = json.writeValueAsString(original);
        assertThat(stored).doesNotContain("\"observe\"");
        var restored = registry.parse(stored);
        assertThat(restored.observe()).isEmpty();
        assertThat(restored.tags()).isEmpty();
        assertThat(restored.enabled()).isTrue();
        assertThat(restored.reasoningProvider()).isEqualTo("openai");
        assertThat(restored.act()).hasSize(1);
    }
}

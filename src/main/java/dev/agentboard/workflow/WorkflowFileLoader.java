package dev.agentboard.workflow;

import dev.agentboard.config.BoardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Adds the workflows found in board.workflows-dir (one JSON file each) when the board does not have them yet.
 * A workflow is data: nothing in the code names an agent or a skill. A workflow you already have is never
 * overwritten, and a file that does not validate is reported and skipped.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class WorkflowFileLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFileLoader.class);

    private final BoardProperties props;
    private final WorkflowService workflows;
    private final ObjectMapper json;

    public WorkflowFileLoader(BoardProperties props, WorkflowService workflows, ObjectMapper json) {
        this.props = props;
        this.workflows = workflows;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String dir = props.getWorkflowsDir();
        if (dir == null || dir.isBlank()) {
            return;
        }
        Path folder = Path.of(dir);
        if (!Files.isDirectory(folder)) {
            log.warn("Workflows folder {} not found; no workflows loaded", folder.toAbsolutePath());
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    WorkflowService.Definition definition = json.readValue(Files.readString(file), WorkflowService.Definition.class);
                    if (definition.id() != null && workflows.definitions().stream().anyMatch(d -> d.id().equals(definition.id()))) {
                        continue;
                    }
                    workflows.save(definition);
                    log.info("Added workflow '{}' from {}", definition.name(), file.getFileName());
                } catch (Exception e) {
                    log.warn("Skipped workflow file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        }
    }
}

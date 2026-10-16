package dev.agentboard.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads every agents/*.yaml file. A broken file is reported and skipped; it never stops
 * the board from starting.
 */
@Component
public class AgentFileLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentFileLoader.class);

    private final ObjectMapper json;

    public AgentFileLoader(ObjectMapper json) {
        this.json = json;
    }

    private static Yaml parser() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(100_000);
        return new Yaml(new SafeConstructor(options));
    }

    public LoadResult load(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.warn("Agents folder {} not found; no file-based agents loaded", dir.toAbsolutePath());
            return new LoadResult(List.of(), List.of(new LoadError(dir.toString(), "folder not found")));
        }
        List<AgentDefinition> agents = new ArrayList<>();
        List<LoadError> errors = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(AgentFileLoader::isYaml).sorted().toList()) {
                readOne(file, agents, errors);
            }
        } catch (IOException e) {
            errors.add(new LoadError(dir.toString(), "cannot list folder: " + e.getMessage()));
        }
        log.info("Loaded {} agents from {} ({} files with problems)", agents.size(), dir.toAbsolutePath(), errors.size());
        errors.forEach(e -> log.warn("Skipped {}: {}", e.file(), e.message()));
        return new LoadResult(agents, errors);
    }

    public String toYaml(AgentDefinition agent) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(json.convertValue(agent, new TypeReference<java.util.Map<String, Object>>() {}));
    }

    private void readOne(Path file, List<AgentDefinition> agents, List<LoadError> errors) {
        String fileName = file.getFileName().toString();
        try {
            if (Files.size(file) > 100_000) {
                errors.add(new LoadError(fileName, "file exceeds 100 KB"));
                return;
            }
            Object data = parser().load(Files.readString(file));
            AgentDefinition agent = json.convertValue(data, AgentDefinition.class);
            if (agent == null) {
                errors.add(new LoadError(fileName, "file is empty"));
                return;
            }
            agent = agent.normalized();
            List<String> problems = new ArrayList<>(AgentValidator.validate(agent));
            String expected = fileName.substring(0, fileName.lastIndexOf('.'));
            if (agent.handle() != null && !agent.handle().equals(expected)) {
                problems.add("handle '" + agent.handle() + "' must match the file name '" + expected + "'");
            }
            if (problems.isEmpty()) {
                agents.add(agent);
            } else {
                errors.add(new LoadError(fileName, String.join("; ", problems)));
            }
        } catch (RuntimeException e) {
            errors.add(new LoadError(fileName, firstLine(e.getMessage())));
        } catch (IOException e) {
            errors.add(new LoadError(fileName, "cannot read file: " + e.getMessage()));
        }
    }

    private static boolean isYaml(Path p) {
        String n = p.getFileName().toString();
        return Files.isRegularFile(p) && (n.endsWith(".yaml") || n.endsWith(".yml"));
    }

    private static String firstLine(String message) {
        if (message == null) return "invalid YAML";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    public record LoadResult(List<AgentDefinition> agents, List<LoadError> errors) {
    }

    public record LoadError(String file, String message) {
    }
}

package dev.agentboard.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.StandardOpenOption;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import dev.agentboard.config.BoardProperties;

/**
 * Diagram box "Agent Registry": knows every agent. At start-up it syncs agents/*.yaml into
 * the database, so the database is the one place every other part reads from.
 */
@Service
@org.springframework.core.annotation.Order(10)
public class AgentRegistry implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);
    public static final int MAX_PAGE = 500;

    private final AgentFileLoader loader;
    private final AgentRepository repository;
    private final ObjectMapper json;
    private final Path agentsDir;
    private final Path exampleAgentsDir;
    private final dev.agentboard.connect.ConnectorCatalog connectors;

    public AgentRegistry(AgentFileLoader loader, AgentRepository repository, ObjectMapper json,
                         @Value("${board.agents-dir}") String agentsDir, BoardProperties props,
                         dev.agentboard.connect.ConnectorCatalog connectors) {
        this.loader = loader;
        this.repository = repository;
        this.json = json;
        this.agentsDir = Path.of(agentsDir);
        this.exampleAgentsDir = props.getExampleAgentsDir().isBlank() ? null : Path.of(props.getExampleAgentsDir());
        this.connectors = connectors;
    }

    @Override
    public void run(ApplicationArguments args) {
        reloadFiles();
    }

    public synchronized void reloadFiles() {
        AgentFileLoader.LoadResult result = withExamples(loader.load(agentsDir));
        for (AgentDefinition agent : result.agents()) {
            repository.upsert(agent, "file", toJson(agent));
        }
        Set<String> present = result.agents().stream().map(AgentDefinition::handle).collect(Collectors.toSet());
        // A missing folder or malformed definition must not erase existing registrations.
        int removed = result.errors().isEmpty() ? repository.deactivateFileAgentsExcept(present) : 0;
        if (removed > 0) {
            log.info("{} file-based agents no longer have a file and were removed from the board", removed);
        }
    }

    /** Adds the read-only example agents; an agent saved in agentsDir wins over an example with the same handle. */
    private AgentFileLoader.LoadResult withExamples(AgentFileLoader.LoadResult own) {
        if (exampleAgentsDir == null) return own;
        AgentFileLoader.LoadResult examples = loader.load(exampleAgentsDir);
        Set<String> handles = own.agents().stream().map(AgentDefinition::handle).collect(Collectors.toSet());
        List<AgentDefinition> agents = new java.util.ArrayList<>(own.agents());
        examples.agents().stream().filter(a -> !handles.contains(a.handle())).forEach(agents::add);
        List<AgentFileLoader.LoadError> errors = new java.util.ArrayList<>(own.errors());
        errors.addAll(examples.errors());
        return new AgentFileLoader.LoadResult(agents, errors);
    }

    public Page<AgentView> list(String query, String owner, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE));
        int safeOffset = Math.max(0, offset);
        return new Page<>(repository.count(query, owner), safeLimit, safeOffset,
                repository.find(query, owner, safeLimit, safeOffset));
    }

    public Optional<AgentDetail> get(String handle) {
        return repository.findView(handle).flatMap(view -> repository.findDefinitionJson(handle)
                .map(defJson -> {
                    try {
                        AgentDefinition def = json.readValue(defJson, AgentDefinition.class);
                        return new AgentDetail(view, def.reason(), loader.toYaml(def), def.observe(), def.act(),def.reasoningProvider());
                    } catch (RuntimeException e) {
                        throw new IllegalStateException("Stored agent definition could not be read", e);
                    }
                }));
    }

    public AgentDefinition parse(String definitionJson) { return json.readValue(definitionJson, AgentDefinition.class); }
    public Optional<AgentDefinition> definition(String handle) { return repository.findDefinitionJson(handle).map(this::parse); }
    /** Definitions of all active agents, whether they came from a YAML file or from the UI. */
    public List<AgentDefinition> allDefinitions() { return repository.findAllDefinitionJson().stream().map(this::parse).toList(); }
    public Optional<AgentView> view(String handle) { return repository.findView(handle); }

    public synchronized AgentView register(AgentDefinition raw) {
        if (raw == null) throw new InvalidAgentException(List.of("agent definition is required"));
        if(raw.reasoningProvider()!=null && !raw.reasoningProvider().equals("openai"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only OpenAI is supported for new agent definitions.");
        AgentDefinition agent = raw.normalized();
        List<String> problems = AgentValidator.validate(agent);
        if (!problems.isEmpty()) throw new InvalidAgentException(problems);
        if (view(agent.handle()).isPresent()) throw new ResponseStatusException(HttpStatus.CONFLICT, "That agent handle is already registered");
        repository.upsert(agent, "external", toJson(agent));
        return view(agent.handle()).orElseThrow();
    }

    public static class InvalidAgentException extends IllegalArgumentException {
        private final List<String> problems;
        public InvalidAgentException(List<String> problems) { super(String.join("; ", problems)); this.problems = List.copyOf(problems); }
        public List<String> problems() { return problems; }
    }

    public synchronized AgentDetail create(AgentDefinition raw) {
        if (raw == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent definition is required");
        if(raw.reasoningProvider()!=null && !raw.reasoningProvider().equals("openai"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only OpenAI is supported for new agent definitions.");
        AgentDefinition agent = raw.normalized();
        List<String> problems = AgentValidator.validate(agent);
        if (!problems.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", problems));
        connectors.validate(agent);
        if (!"company".equals(agent.visibility())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This local shared-board milestone supports company visibility only");
        }
        Path file = agentsDir.resolve(agent.handle() + ".yaml");
        try {
            if (repository.findView(agent.handle()).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That agent handle is already registered");
            }
            Files.createDirectories(agentsDir);
            Files.writeString(file, loader.toYaml(agent), StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That agent handle already has a configuration file");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the agent configuration");
        }
        reloadFiles();
        return get(agent.handle()).orElseThrow(() -> new IllegalStateException("Agent did not load"));
    }

    /** True for agents saved in the agents folder (made on this board); examples and API registrations are not. */
    public boolean deletable(String handle) {
        return handle != null && handle.matches("[a-z0-9][a-z0-9-]{0,63}") && Files.isRegularFile(agentsDir.resolve(handle + ".yaml"));
    }

    /** True for a read-only example agent (board.example-agents-dir), such as the demo team. */
    public boolean isExample(String handle) {
        return exampleAgentsDir != null && handle != null && handle.matches("[a-z0-9][a-z0-9-]{0,63}")
                && Files.isRegularFile(exampleAgentsDir.resolve(handle + ".yaml")) && !deletable(handle);
    }

    /** Removes an agent saved in the agents folder. Its runs and posts stay; example agents are read-only. */
    public synchronized void delete(String handle) {
        if (handle == null || !handle.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid agent handle");
        Path file = agentsDir.resolve(handle + ".yaml");
        if (!Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only agents created on this board can be deleted; example agents are read-only");
        }
        try { Files.delete(file); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not delete the agent file"); }
        repository.deactivate(handle);
    }

    private String toJson(AgentDefinition agent) {
        try { return json.writeValueAsString(agent); }
        catch (RuntimeException e) { throw new IllegalStateException("Could not serialize agent", e); }
    }

    public record Page<T>(long total, int limit, int offset, List<T> items) {
    }

    public record AgentDetail(AgentView agent, String reason, String configYaml,
                              List<AgentDefinition.ObserveStep> observe, List<AgentDefinition.ActStep> act,String reasoningProvider) {
    }
}

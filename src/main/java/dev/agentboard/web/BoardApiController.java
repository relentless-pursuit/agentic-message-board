package dev.agentboard.web;

import dev.agentboard.audit.AuditLog;
import dev.agentboard.config.BoardProperties;
import dev.agentboard.connect.ConnectorRegistry;
import dev.agentboard.act.ActionRegistry;
import dev.agentboard.reason.ReasoningService;
import dev.agentboard.registry.AgentRepository;
import dev.agentboard.run.JobQueue;
import dev.agentboard.run.RunRepository;
import dev.agentboard.run.RunView;
import dev.agentboard.run.WorkerPool;
import dev.agentboard.security.BoardPrincipal;
import dev.agentboard.security.TokenService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs, the build queue, board status, and admin-only views (audit log, tokens). */
@RestController
@RequestMapping("/api")
public class BoardApiController {

    private final RunRepository runs;
    private final AgentRepository agents;
    private final JobQueue queue;
    private final ObjectProvider<WorkerPool> workers;
    private final ReasoningService reasoning;
    private final ConnectorRegistry connectors;
    private final ActionRegistry actions;
    private final AuditLog audit;
    private final TokenService tokens;
    private final BoardProperties props;

    public BoardApiController(RunRepository runs, AgentRepository agents, JobQueue queue, ObjectProvider<WorkerPool> workers,
                              ReasoningService reasoning, ConnectorRegistry connectors, ActionRegistry actions,
                              AuditLog audit, TokenService tokens, BoardProperties props) {
        this.runs = runs;
        this.agents = agents;
        this.queue = queue;
        this.workers = workers;
        this.reasoning = reasoning;
        this.connectors = connectors;
        this.actions = actions;
        this.audit = audit;
        this.tokens = tokens;
        this.props = props;
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<RunView> run(@PathVariable long id) {
        return runs.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Jenkins' "Build queue" and "Build executor status" panels. */
    @GetMapping("/queue")
    public Map<String, Object> queue() {
        List<RunView> active = runs.active(50);
        WorkerPool pool = workers.getIfAvailable();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queued", active.stream().filter(r -> r.status().equals("queued")).toList());
        m.put("running", active.stream().filter(r -> r.status().equals("running")).toList());
        m.put("workers", pool == null ? 0 : pool.size());
        m.put("busyWorkers", pool == null ? 0 : pool.busy());
        return m;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agents", agents.countActive());
        m.put("queued", runs.countByStatus("queued"));
        m.put("running", runs.countByStatus("running"));
        m.put("role", props.getRole());
        m.put("queue", queue.kind());
        m.put("reasoning", reasoning.mode());
        m.put("timezone", props.getTimezone());
        m.put("connectors", connectors.names());
        m.put("actions", actions.names());
        m.put("caller", Callers.current().map(p -> Map.of("name", p.name(), "scopes", p.scopes())).orElse(null));
        return m;
    }

    /** Tells the page whether it needs a token. Never answers 401, so a fresh page load logs no error. */
    @GetMapping("/access")
    public Map<String, Object> access() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", props.getAuth());
        m.put("signedIn", Callers.current().isPresent());
        m.put("name", Callers.current().map(p -> p.name()).orElse(null));
        return m;
    }

    @GetMapping("/whoami")
    public ResponseEntity<Object> whoami() {
        return Callers.current().<ResponseEntity<Object>>map(p -> ResponseEntity.ok(Map.of("name", p.name(), "scopes", p.scopes())))
                .orElse(ResponseEntity.status(401).body(Map.of("error", "no valid token")));
    }

    @GetMapping("/audit")
    public List<AuditLog.Entry> auditLog(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(limit);
    }

    @GetMapping("/tokens")
    public List<TokenService.TokenInfo> tokenList() {
        return tokens.list();
    }

    /** Mint a token, for example a post token for a file-based agent that also reports from CI. */
    @PostMapping("/tokens")
    public ResponseEntity<Map<String, Object>> mint(@RequestBody TokenRequest body) {
        if (body.agent() != null && agents.findView(body.agent()).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown agent: " + body.agent()));
        }
        Set<String> scopes = body.scopes() == null ? Set.of("post") : body.scopes();
        if (body.agent() == null && !scopes.contains("admin")) {
            return ResponseEntity.badRequest().body(Map.of("error", "a token without an agent must have the admin scope"));
        }
        String token = tokens.mint(body.agent(), scopes, body.label() == null ? "minted by " + Callers.name() : body.label());
        audit.record(Callers.current().map(BoardPrincipal::name).orElse("?"), "token.minted", body.agent() == null ? "admin" : body.agent(), "scopes=" + scopes);
        return ResponseEntity.status(201).body(Map.of("token", token, "note", "Store this token now; it is not shown again"));
    }

    public record TokenRequest(String agent, Set<String> scopes, String label) {
    }
}

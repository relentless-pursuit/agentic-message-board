package dev.agentboard.web;

import dev.agentboard.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs DocDrift (src/docdrift, problem 2.12) from the board: which docs did a code change make outdated?
 * The board starts `python3 src/docdrift check ...` as a separate process with an argument list (no shell); DocDrift
 * posts its result back to this board's feed as the agent "Docs Drift". One check runs at a time.
 */
@RestController
@RequestMapping("/api/docdrift")
public class DocDriftController {

    private static final Pattern REPO = Pattern.compile("^(https://github\\.com/)?[A-Za-z0-9][\\w.-]{0,99}/[A-Za-z0-9][\\w.-]{0,99}?(\\.git)?/?$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9][\\w./-]{0,99}$");
    private static final Path SCRIPT = Path.of("src/docdrift/__main__.py");

    private final AuditLog audit;
    private final ObjectMapper json;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public DocDriftController(AuditLog audit, ObjectMapper json) {
        this.audit = audit;
        this.json = json;
    }

    public record Start(String repo, String base, String head, String reasoner) {}

    static final class Run {
        final String id, repo, base, head, reasoner;
        final Instant started = Instant.now();
        volatile Instant finished;
        volatile String status = "running";
        final Deque<String> output = new ArrayDeque<>();
        Run(String id, String repo, String base, String head, String reasoner) {
            this.id = id; this.repo = repo; this.base = base; this.head = head; this.reasoner = reasoner;
        }
    }

    @GetMapping
    public Map<String, Object> list() {
        var items = runs.values().stream().sorted(Comparator.comparing((Run r) -> r.started).reversed()).limit(10).map(this::view).toList();
        return Map.of("available", available(), "runs", items);
    }

    @PostMapping("/runs")
    public Map<String, Object> start(@RequestBody Start in, HttpServletRequest request) {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DocDrift is not available here: it needs src/docdrift and python3 (start the board from the repository with bash start.sh).");
        String repo = in.repo() == null ? "" : in.repo().trim(), base = trim(in.base()), head = trim(in.head());
        if (!REPO.matcher(repo).matches()) throw bad("Enter a GitHub repository such as pallets/click.");
        if (!REF.matcher(base).matches() || !REF.matcher(head).matches() || base.contains("..") || head.contains(".."))
            throw bad("Enter a tag, branch or commit for both versions, such as 8.1.8 and main.");
        String reasoner = Set.of("auto", "openai", "rules").contains(in.reasoner()) ? in.reasoner() : "auto";
        synchronized (runs) {
            if (runs.values().stream().anyMatch(r -> r.status.equals("running")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A docs check is already running. Wait for it to finish.");
            String id = UUID.randomUUID().toString().substring(0, 8);
            Run run = new Run(id, repo, base, head, reasoner);
            runs.put(id, run);
            String board = "http://127.0.0.1:" + request.getLocalPort();
            List<String> cmd = List.of("python3", "src/docdrift", "check", "--repo", repo, "--base", base, "--head", head,
                    "--reasoner", reasoner, "--out", "data/docdrift/runs/" + id, "--post-to-board", board);
            Thread worker = new Thread(() -> execute(run, cmd), "docdrift-" + id);
            worker.setDaemon(true);
            worker.start();
            audit.record(Callers.name(), "docdrift.started", repo, base + " → " + head, Callers.ip(request));
            return view(run);
        }
    }

    private void execute(Run run, List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (var out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = out.readLine()) != null; ) {
                    synchronized (run.output) {
                        run.output.addLast(line);
                        while (run.output.size() > 40) run.output.removeFirst();
                    }
                }
            }
            boolean done = p.waitFor(10, TimeUnit.MINUTES);
            if (!done) p.destroyForcibly();
            run.status = done && p.exitValue() == 0 ? "done" : "failed";
        } catch (Exception e) {
            run.output.addLast("Could not start DocDrift: " + e.getMessage());
            run.status = "failed";
        }
        run.finished = Instant.now();
    }

    private Map<String, Object> view(Run r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.id); m.put("repo", r.repo); m.put("base", r.base); m.put("head", r.head); m.put("reasoner", r.reasoner);
        m.put("status", r.status); m.put("startedAt", r.started.toString()); m.put("finishedAt", r.finished == null ? null : r.finished.toString());
        synchronized (r.output) { m.put("output", List.copyOf(r.output)); }
        Path report = Path.of("data/docdrift/runs", r.id, "report.json");
        if (Files.isRegularFile(report)) {
            try {
                JsonNode tree = json.readTree(Files.readString(report));
                m.put("summary", tree.get("summary"));
                var findings = new ArrayList<Map<String, Object>>();
                for (JsonNode f : tree.path("findings")) {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("where", f.path("file").asString() + ":" + f.path("line").asInt());
                    row.put("change", f.path("change").path("description").asString());
                    JsonNode v = f.path("verdict");
                    row.put("outdated", v.isMissingNode() || v.isNull() ? null : v.path("outdated").asBoolean());
                    row.put("reason", v.path("reason").asString(""));
                    JsonNode g = f.path("guardrails");
                    row.put("fixed", !g.isMissingNode() && !g.isNull() && g.path("ok").asBoolean());
                    var failures = new ArrayList<String>();
                    g.path("failures").forEach(x -> failures.add(x.asString()));
                    row.put("blocked", failures);
                    findings.add(row);
                }
                m.put("findings", findings);
            } catch (Exception e) {
                m.put("reportError", "The report could not be read.");
            }
        }
        return m;
    }

    private static boolean available() {
        if (!Files.isRegularFile(SCRIPT)) return false;
        String path = Objects.toString(System.getenv("PATH"), "");
        return Arrays.stream(path.split(java.io.File.pathSeparator)).anyMatch(d -> Files.isExecutable(Path.of(d, "python3")));
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}

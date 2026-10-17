package dev.agentboard.web;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.run.RunRepository;
import dev.agentboard.run.RunView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runs, and who is calling. */
@RestController
@RequestMapping("/api")
public class BoardApiController {

    private final RunRepository runs;
    private final BoardProperties props;

    public BoardApiController(RunRepository runs, BoardProperties props) {
        this.runs = runs;
        this.props = props;
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<RunView> run(@PathVariable long id) {
        return runs.find(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
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
}

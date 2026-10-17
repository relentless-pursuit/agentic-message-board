package dev.agentboard.web;

import dev.agentboard.registry.AgentRegistry;
import dev.agentboard.registry.AgentView;
import dev.agentboard.registry.AgentDefinition;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Diagram box "REST API". The Jenkins-style pages use these same endpoints, so the UI has
 * no special back door: anything the UI can see, any other system can see the same way.
 */
@RestController
@RequestMapping("/api")
public class AgentApiController {

    private final AgentRegistry registry;
    private final dev.agentboard.run.RunService runService;
    private final dev.agentboard.run.RunRepository runs;

    public AgentApiController(AgentRegistry registry, dev.agentboard.run.RunService runService, dev.agentboard.run.RunRepository runs) {
        this.registry = registry;
        this.runService=runService; this.runs=runs;
    }

    @GetMapping("/agents")
    public AgentRegistry.Page<AgentView> list(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String owner,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        return registry.list(q, owner, limit, offset);
    }

    @GetMapping("/agents/{handle}")
    public ResponseEntity<AgentRegistry.AgentDetail> get(@PathVariable String handle) {
        return registry.get(handle).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/agents")
    public ResponseEntity<AgentRegistry.AgentDetail> create(@RequestBody AgentDefinition definition) {
        return ResponseEntity.status(201).body(registry.create(definition));
    }

    @PostMapping("/agents/{handle}/runs")
    public ResponseEntity<?> runNow(@PathVariable String handle, @RequestBody(required=false) Map<String,Object> event) {
        var agent=registry.view(handle).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        if(!agent.enabled() || agent.source().equals("external")) return ResponseEntity.status(409).body(Map.of("error","agent disabled or external"));
        var result=runService.trigger(handle,"manual","manual:"+java.util.UUID.randomUUID(),"requested by "+Callers.name(),0,
                event==null?null:new tools.jackson.databind.ObjectMapper().writeValueAsString(event));
        return ResponseEntity.status(result.outcome().equals("busy")?409:202).body(result);
    }

    @GetMapping("/agents/{handle}/runs")
    public Map<String,Object> runsOf(@PathVariable String handle,@RequestParam(defaultValue="25") int limit,@RequestParam(defaultValue="0") int offset) {
        int safe=Math.max(1,Math.min(limit,200)), start=Math.max(0,offset);
        return Map.of("total",runs.countByAgent(handle),"items",runs.byAgent(handle,safe,start),"limit",safe,"offset",start);
    }
}

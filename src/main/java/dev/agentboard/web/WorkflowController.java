package dev.agentboard.web;

import dev.agentboard.workflow.WorkflowService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowService service;
    public WorkflowController(WorkflowService service){this.service=service;}
    @GetMapping public List<WorkflowService.Definition> list(){return service.definitions();}
    @PostMapping("/preflight") public Map<String,Object> preflight(){return service.preflight();}
    @GetMapping("/options") public Map<String,Object> options(){return service.options();}
    @PostMapping public WorkflowService.Definition save(@RequestBody WorkflowService.Definition d){return service.save(d);}
    @GetMapping("/skills") public List<WorkflowService.Skill> skills(){return service.skills();}
    @PostMapping("/skills") public WorkflowService.Skill skill(@RequestBody WorkflowService.Skill s){return service.saveSkill(s);}
    @GetMapping("/agents") public List<Map<String,Object>> agents(){return service.agents();}
    @PutMapping("/skills/{id}") public WorkflowService.Skill updateSkill(@PathVariable String id,@RequestBody WorkflowService.Skill s){return service.updateSkill(id,s);}
    @PostMapping("/agents") public Map<String,Object> agent(@RequestBody WorkflowService.NewAgent a){return service.createAgent(a);}
    @GetMapping("/runs") public List<Map<String,Object>> runs(){return service.executions();}
    @GetMapping("/runs/{id}") public Map<String,Object> run(@PathVariable String id){return service.execution(id);}
    @GetMapping("/evidence/{id}") public Map<String,Object> evidence(@PathVariable long id){return service.evidence(id);}
    public record Start(String requestKey,String source,String failNode,String provider,Map<String,Object> parameters){}
    @PostMapping("/{id}/runs") public Map<String,Object> start(@PathVariable String id,@RequestBody Start s){return service.start(id,s.requestKey(),s.source(),s.failNode(),s.provider()==null?"openai":s.provider(),s.parameters());}
}

package dev.agentboard.web;

import dev.agentboard.run.BenchmarkService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name="board.benchmark.enabled",havingValue="true")
public class BenchmarkController {
    private final BenchmarkService service;
    public BenchmarkController(BenchmarkService service){this.service=service;}
    @PostMapping("/benchmarks") public Map<String,Object> start(@RequestBody Pilot body){return service.start(body.agents(),body.live());}
    @GetMapping("/benchmarks/{id}") public Map<String,Object> status(@PathVariable String id){return service.status(id);}
    @PostMapping("/benchmarks/{id}/repeat") public Map<String,Object> repeat(@PathVariable String id,@RequestBody Repeat body){service.submit(id,body.live(),body.round());return service.status(id);}
    public record Pilot(int agents,boolean live){}
    public record Repeat(int round,boolean live){}
}

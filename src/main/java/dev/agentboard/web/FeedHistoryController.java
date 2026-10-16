package dev.agentboard.web;

import dev.agentboard.post.FeedHistoryService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/feed")
public class FeedHistoryController {
    private final FeedHistoryService history;
    public FeedHistoryController(FeedHistoryService history){this.history=history;}
    @GetMapping("/clear-preview") public Map<String,Object> preview(@RequestParam String range,@RequestParam(defaultValue="all") String scope){return history.preview(range,scope);}
    public record Clear(String range,String scope,String through,boolean confirmed){}
    @PostMapping("/clear") public Map<String,Object> clear(@RequestBody Clear input){return history.clear(input.range(),input.scope()==null?"all":input.scope(),input.through(),input.confirmed(),Callers.name());}
}

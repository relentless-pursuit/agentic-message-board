package dev.agentboard.web;

import dev.agentboard.reason.ModelUsage;
import dev.agentboard.reason.OpenAiReasoner;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Usage accounting remains available even when synthetic benchmark jobs are disabled. */
@RestController
public class ModelUsageController {
    private final ModelUsage usage;
    private final OpenAiReasoner openai;
    public ModelUsageController(ModelUsage usage,OpenAiReasoner openai){this.usage=usage;this.openai=openai;}
    @GetMapping("/api/model-usage")
    public Map<String,Object> usage(){var result=usage.snapshot();result.put("keyConfigured",openai.configured());return result;}
}

package dev.agentboard.reason;

import dev.agentboard.connect.Observation;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Service;

/** All new agent reasoning uses a recorded OpenAI execution. */
@Service
public class ReasoningService {
    private final AgentModelRuntime runtime;
    private final OpenAiReasoner model;
    public ReasoningService(AgentModelRuntime runtime,OpenAiReasoner model){this.runtime=runtime;this.model=model;}
    public Reasoning reason(AgentDefinition agent,Observation observed,RunContext context){
        try{return runtime.execute(agent,observed,context);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("OpenAI execution interrupted",e);}
        catch(Exception e){throw new IllegalStateException(e.getMessage(),e);}
    }
    public String mode(){return "OpenAI · "+model.model()+(model.configured()?"":" · key required");}
    public String cacheKey(RunContext context){return "openai:"+model.model();}
}

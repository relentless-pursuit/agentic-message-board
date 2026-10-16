package dev.agentboard.workflow;

import dev.agentboard.connect.*;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;
import java.util.*;

/** One explicit source contract, used by the normal Observe/Reason/Act runner. */
@Component
public class WorkflowInputConnector implements Connector {
    public String name(){return "workflow_input";}
    public Observation fetch(Map<String,Object> settings,RunContext context){
        var event=context.event();
        if(!event.containsKey("source"))throw new IllegalArgumentException("Run this agent from a workflow with source text.");
        List<String> facts=new ArrayList<>();facts.add("Source: "+event.get("source"));
        if(event.get("upstream") instanceof List<?> upstream)for(Object item:upstream){
            @SuppressWarnings("unchecked") var output=(Map<String,Object>)item;
            facts.add("From "+output.get("name")+" (run #"+output.get("runId")+", post #"+output.get("postId")+"): "+output.get("text"));
        }
        return new Observation("Workflow input for "+context.agent().name(),facts,event.toString(),"low",true);
    }
}

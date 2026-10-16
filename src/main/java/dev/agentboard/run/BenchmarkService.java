package dev.agentboard.run;

import dev.agentboard.registry.*;
import dev.agentboard.reason.ModelUsage;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Small explicit local pilot. All inputs are generated, with no connector network requests. */
@Service
public class BenchmarkService {
    private final AgentRepository agents;
    private final RunService runs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ModelUsage usage;
    private final Map<String,List<String>> batches=new ConcurrentHashMap<>();
    public BenchmarkService(AgentRepository agents,RunService runs,JdbcTemplate jdbc,ObjectMapper json,ModelUsage usage) {
        this.agents=agents;this.runs=runs;this.jdbc=jdbc;this.json=json;this.usage=usage;
    }
    public synchronized Map<String,Object> start(int count,boolean live) {
        if(!live)throw new IllegalArgumentException("Only model-backed benchmark execution is available.");
        if(count<1 || count>10) throw new IllegalArgumentException("Pilot count must be between 1 and 10");
        String id=UUID.randomUUID().toString().substring(0,8);
        List<String> handles=new ArrayList<>();
        for(int i=0;i<count;i++) {
            String handle="bench-"+id+"-"+i;
            var definition=new AgentDefinition(handle,"Pilot Agent "+(i+1),"Report the supplied synthetic CI result concisely.","local-test",null,"company",List.of("pilot"),
                    new AgentDefinition.Trigger(null,null,null,"pilot.manual",null),
                    List.of(new AgentDefinition.ObserveStep("event",Map.of())),"Only state the status and the failure explicitly present in the facts.",
                    List.of(new AgentDefinition.ActStep("post",Map.of())),true);
            agents.upsert(definition,"benchmark",json.writeValueAsString(definition)); handles.add(handle);
        }
        batches.put(id,List.copyOf(handles));
        submit(id,live,0);
        return Map.of("batch",id,"agents",count,"provider","openai","syntheticObservations",true);
    }
    public synchronized void submit(String id,boolean live,int round) {
        for(String handle:handles(id)) {
            var event=Map.of("event","pilot.manual","id",handle,"status","FAILED","source","generated-test-fixture",
                    "job","checkout-ci","summary","Synthetic checkout test failed: expected HTTP 200, received 503.");
            runs.trigger(handle,"bench-live","pilot:"+id+":"+round,"generated local pilot",0,json.writeValueAsString(event));
        }
    }
    public Map<String,Object> status(String id) {
        handles(id);
        var rows=jdbc.queryForList("SELECT status,engine,COUNT(*) AS count FROM runs WHERE agent_handle LIKE ? GROUP BY status,engine ORDER BY status,engine","bench-"+id+"-%");
        var counts=jdbc.queryForMap("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN status IN ('queued','running') THEN 1 ELSE 0 END),0) AS pending, COALESCE(SUM(CASE WHEN status='failed' THEN 1 ELSE 0 END),0) AS failed FROM runs WHERE agent_handle LIKE ?","bench-"+id+"-%");
        return Map.of("batch",id,"agents",handles(id).size(),"runs",counts,"outcomes",rows,"usage",usage.snapshot());
    }
    public List<String> handles(String id) {
        var found=batches.get(id);if(found==null) throw new IllegalArgumentException("Unknown pilot batch");return found;
    }
}

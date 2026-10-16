package dev.agentboard.workflow;

import dev.agentboard.connect.Observation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** Records the normalized fetched facts, not arbitrary web page instructions or credentials. */
@Component
public class WorkflowObservationStore {
    private final JdbcTemplate db;private final ObjectMapper json;
    public WorkflowObservationStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public void save(long runId,String connector,Map<String,Object> settings,Observation observation){
        String now=Instant.now().toString();var detail=Map.of("connector",connector,"settings",settings,"collectedAt",now,"summary",observation.summary(),"facts",observation.facts());
        db.update("INSERT INTO workflow_observations(run_id,collected_at,detail) VALUES(?,?,?)",runId,now,json.writeValueAsString(detail));
    }
}

package dev.agentboard.reason;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Component
public class AgentReportStore {
    private final JdbcTemplate db;private final ObjectMapper json;
    public AgentReportStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    public void save(long run,Map<String,Object> report){String detail=json.writeValueAsString(report);if(db.update("UPDATE agent_reports SET detail=? WHERE run_id=?",detail,run)==0)db.update("INSERT INTO agent_reports(run_id,detail) VALUES(?,?)",run,detail);}
    @SuppressWarnings("unchecked") public Map<String,Object> get(long run){return db.query("SELECT detail FROM agent_reports WHERE run_id=?",(rs,n)->(Map<String,Object>)json.readValue(rs.getString(1),LinkedHashMap.class),run).stream().findFirst().orElse(Map.of());}
}

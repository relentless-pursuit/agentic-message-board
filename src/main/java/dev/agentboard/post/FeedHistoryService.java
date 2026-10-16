package dev.agentboard.post;

import dev.agentboard.audit.AuditLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Clears the shared feed view while keeping posts available as immutable execution evidence. */
@Service
public class FeedHistoryService {
    private final JdbcTemplate db;private final AuditLog audit;
    public FeedHistoryService(JdbcTemplate db,AuditLog audit){this.db=db;this.audit=audit;}
    private record Selection(String where,List<Object> params,Instant through){}
    private Selection selection(String range,String scope,Instant through){
        if(through==null || through.isAfter(Instant.now().plusSeconds(5)) || through.isBefore(Instant.now().minusSeconds(900)))
            throw new IllegalArgumentException("This preview expired. Reopen Clear feed to refresh it.");
        Duration duration=switch(Objects.toString(range,"")){
            case "hour" -> Duration.ofHours(1);case "day" -> Duration.ofDays(1);case "week" -> Duration.ofDays(7);case "all" -> null;
            default -> throw new IllegalArgumentException("Choose 1 hour, 24 hours, 7 days or all time.");
        };
        String where="archived_at IS NULL AND created_at <= ?";
        List<Object> params=new ArrayList<>();params.add(through.atOffset(ZoneOffset.UTC));
        if(duration!=null){where+=" AND created_at >= ?";params.add(through.minus(duration).atOffset(ZoneOffset.UTC));}
        if("demo".equals(scope))where+=" AND run_id IN (SELECT id FROM runs WHERE (engine='simulation' AND agent_handle LIKE 'demo-%') OR trigger_kind IN ('bench-local','bench-live'))";
        else if(!"all".equals(scope))throw new IllegalArgumentException("Unknown feed-clear scope.");
        return new Selection(where,params,through);
    }
    public Map<String,Object> preview(String range,String scope){
        var s=selection(range,scope,Instant.now());
        long count=db.queryForObject("SELECT COUNT(*) FROM posts WHERE "+s.where(),Long.class,s.params().toArray());
        return Map.of("range",range,"scope",scope,"count",count,"through",s.through().toString(),"retainsRunEvidence",true);
    }
    @Transactional
    public Map<String,Object> clear(String range,String scope,String through,boolean confirmed,String actor){
        if(!confirmed)throw new IllegalArgumentException("Confirm clearing the selected feed range.");
        Instant end;try{end=Instant.parse(through);}catch(Exception e){throw new IllegalArgumentException("Refresh the Clear feed preview.");}
        var s=selection(range,scope,end);var params=new ArrayList<Object>();params.add(OffsetDateTime.now(ZoneOffset.UTC));params.addAll(s.params());
        int count=db.update("UPDATE posts SET archived_at=? WHERE "+s.where(),params.toArray());
        audit.record(actor,"feed.cleared",scope,"range="+range+", through="+through+", archived="+count+"; execution evidence retained");
        return Map.of("cleared",count,"range",range,"through",through,"retainsRunEvidence",true);
    }
}

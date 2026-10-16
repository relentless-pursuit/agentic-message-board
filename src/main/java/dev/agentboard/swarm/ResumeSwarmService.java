package dev.agentboard.swarm;

import dev.agentboard.reason.ModelUsage;
import dev.agentboard.reason.OpenAiReasoner;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A ten-agent dependency graph: eight agents, human clarification/review, two agents, final approval. */
@Service
public class ResumeSwarmService {
    private final JdbcTemplate db;private final ObjectMapper json;private final SwarmSkills skills;
    private final OpenAiReasoner model;private final ModelUsage usage;
    private final ExecutorService coordinator=Executors.newSingleThreadExecutor(r->daemon(r,"resume-coordinator"));
    private final ExecutorService agents=Executors.newFixedThreadPool(2,r->daemon(r,"resume-agent"));
    private final Map<Long,AtomicBoolean> controls=new ConcurrentHashMap<>();
    private static Thread daemon(Runnable r,String name){Thread t=new Thread(r,name);t.setDaemon(true);return t;}
    public ResumeSwarmService(JdbcTemplate db,ObjectMapper json,SwarmSkills skills,OpenAiReasoner model,ModelUsage usage) {
        this.db=db;this.json=json;this.skills=skills;this.model=model;this.usage=usage;
        for(Long id:db.query("SELECT id FROM swarm_runs WHERE status IN ('queued','running','finalizing')",(rs,n)->rs.getLong(1)))
            mutate(id,j->{j.put("status","interrupted");j.put("error","The server restarted. This workflow was not retried; completed calls may have accrued usage.");
                steps(j).stream().filter(s->List.of("running","waiting").contains(s.get("status"))).forEach(s->s.put("status","interrupted"));});
    }
    public Map<String,Object> options(){
        return Map.of("model",model.model(),"provider","openai","configured",model.configured(),"agents",skills.snapshots().stream().map(this::publicStep).toList(),"maxCalls",10,"budgetUsd",0.10,"capacity",usage.swarmCapacity(),"billing","OpenAI API usage; credential and shared budget required.");
    }
    public synchronized Map<String,Object> start(String requestKey,String name,String resume,String jd) {
        try{UUID.fromString(requestKey);}catch(Exception e){throw new IllegalArgumentException("Invalid submission key.");}
        var existing=db.query("SELECT id FROM swarm_runs WHERE request_key=?",(rs,n)->rs.getLong(1),requestKey);
        if(!existing.isEmpty())return get(existing.get(0));
        checkText(name,"Name",80);checkText(resume,"Résumé",8000);checkText(jd,"Job description",6000);
        if(!model.configured())throw new IllegalArgumentException("OPENAI_API_KEY is required. No local execution is available.");
        boolean live=true;
        if(live && !model.model().equals("gpt-5.4-nano"))throw new IllegalArgumentException("This demo's cost guard supports gpt-5.4-nano only.");
        if(live && !Boolean.TRUE.equals(usage.swarmCapacity().get("canStart")))throw new IllegalArgumentException("The shared API ledger needs at least 10 remaining attempts and $0.10 of unreserved budget to start this workflow. Review the board's budget settings.");
        if(!controls.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"A swarm is already running. Wait for its review step or completion.");
        Map<String,Object> j=new LinkedHashMap<>();j.put("name",name.trim());j.put("resume",resume.trim());j.put("jd",jd.trim());
        j.put("provider","openai");j.put("model",model.model());j.put("createdAt",Instant.now().toString());j.put("status","queued");j.put("steps",skills.snapshots());
        var keys=new GeneratedKeyHolder();
        db.update(c->{var s=c.prepareStatement("INSERT INTO swarm_runs(request_key,status,created_at,detail) VALUES(?,'queued',?,?)",new String[]{"id"});
            s.setString(1,requestKey);s.setString(2,(String)j.get("createdAt"));s.setString(3,json.writeValueAsString(j));return s;},keys);
        long id=keys.getKey().longValue();j.put("id",id);save(id,j);queue(id,false);return get(id);
    }
    private void queue(long id,boolean finalPhase){var control=new AtomicBoolean();controls.put(id,control);coordinator.execute(()->run(id,finalPhase,control));}
    private void run(long id,boolean finalPhase,AtomicBoolean cancelled) {
        try {
            mutate(id,j->{if(!cancelled.get())j.put("status",finalPhase?"finalizing":"running");});
            List<List<Integer>> waves=finalPhase?List.of(List.of(9),List.of(10)):List.of(List.of(1,2,3),List.of(4),List.of(5),List.of(6,7),List.of(8));
            for(var wave:waves){
                if(cancelled.get())return;
                var futures=wave.stream().map(n->CompletableFuture.runAsync(()->step(id,n,cancelled),agents)).toArray(CompletableFuture[]::new);
                CompletableFuture.allOf(futures).join();
            }
            mutate(id,j->{if(!cancelled.get()){j.put("status",finalPhase?"final_review":"review");j.put("pausedAt",Instant.now().toString());}});
        }catch(Exception e){
            Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();String message=Objects.toString(cause.getMessage(),"Swarm failed");
            mutate(id,j->{if(!cancelled.get()){j.put("status","failed");j.put("error",message);steps(j).stream().filter(s->"waiting".equals(s.get("status"))).forEach(s->s.put("status","blocked"));}});
        }finally{controls.remove(id,cancelled);}
    }
    private void step(long id,int n,AtomicBoolean cancelled) {
        if(cancelled.get())return;
        var definition=SwarmSkills.AGENTS.get(n-1);Map<String,Object> j=load(id);
        for(int dependency:definition.dependsOn())if(!"success".equals(steps(j).get(dependency-1).get("status")))throw new IllegalStateException("Dependency "+dependency+" is incomplete.");
        if(n>=9 && (!j.containsKey("approvedResume") || !j.containsKey("userMotivation")))throw new IllegalStateException("Résumé approval and a motivation answer are required before the cover letter.");
        String input=json.writeValueAsString(inputs(j,n));
        mutate(id,state->{if(cancelled.get())return;var s=steps(state).get(n-1);s.put("status","running");s.put("startedAt",Instant.now().toString());s.put("receivedFrom",definition.dependsOn());});
        try {
            if(cancelled.get())return;
            String instructions=(String)steps(j).get(n-1).get("instructions");
            instructions+="\nKeep the answer compact. Return only fields in the provided schema. A short summary (<=300 characters) and at most six concise evidence items (<=220 characters each) are enough. Do not restate the input document. Only the editor returns edits; only the cover-letter writer returns document text.";
            OpenAiReasoner.Completion response;
            if(!"openai".equals(j.get("provider")))throw new IllegalStateException("This historical provider cannot execute new steps.");
            response=model.structured(id,instructions,input,SwarmSkills.schema(n),definition.outputTokens());
            @SuppressWarnings("unchecked") Map<String,Object> result=json.convertValue(response.answer(),LinkedHashMap.class);
            result.putIfAbsent("edits",List.of());result.putIfAbsent("document","");
            validateResult(result,n);
            if(n==5)validateEdits(result,(String)j.get("resume"));
            mutate(id,state->{var s=steps(state).get(n-1);s.put("status",cancelled.get()?"cancelled":"success");s.put("result",result);s.put("usage",response.usage());s.put("provider",state.get("provider"));s.put("finishedAt",Instant.now().toString());});
        }catch(Exception e){mutate(id,state->{var s=steps(state).get(n-1);s.put("status",cancelled.get()?"cancelled":"failed");s.put("error",Objects.toString(e.getMessage(),"Agent failed"));
            if(e instanceof OpenAiReasoner.ResponseProblem problem){s.put("usage",problem.usage);s.put("providerReason",problem.reason);}});throw new CompletionException(e);}
    }
    private Map<String,Object> inputs(Map<String,Object> j,int n) {
        var input=new LinkedHashMap<String,Object>();
        if(List.of(1,2,5,6).contains(n))input.put("originalResume",j.get("resume"));
        if(List.of(1,3,4,5,7,9,10).contains(n))input.put("jobDescription",j.get("jd"));
        if(n>=9){input.put("userApprovedResume",j.get("approvedResume"));input.put("userMotivation",j.get("userMotivation"));}
        for(int dependency:SwarmSkills.AGENTS.get(n-1).dependsOn())input.put("agent"+dependency,steps(j).get(dependency-1).get("result"));
        return input;
    }
    private static void validateResult(Map<String,Object> result,int n) {
        if(!(result.get("summary") instanceof String summary) || summary.length()>1800 || !(result.get("items") instanceof List<?> items) || items.size()>8 ||
                items.stream().anyMatch(i->!(i instanceof String text) || text.length()>1000) || !(result.get("edits") instanceof List<?> edits) || edits.size()>6 ||
                !(result.get("document") instanceof String document) || document.length()>6000)
            throw new IllegalStateException("Agent returned an invalid or oversized result.");
        if(n==8 && (!(result.get("question") instanceof String question) || question.isBlank() || question.length()>300))throw new IllegalStateException("The intent clarifier returned an invalid question.");
        if(n==9 && ((String)result.get("document")).isBlank())throw new IllegalStateException("Cover-letter writer returned an empty letter.");
        if(n!=5 && !((List<?>)result.get("edits")).isEmpty())throw new IllegalStateException("Only the résumé editor can propose edits.");
    }
    @SuppressWarnings("unchecked")
    static void validateEdits(Map<String,Object> result,String original) {
        int index=0;List<int[]> occupied=new ArrayList<>();
        for(var edit:(List<Map<String,Object>>)result.get("edits")) {
            for(String key:List.of("before","after","reason","evidence"))if(!(edit.get(key) instanceof String text) || text.length()>1600)throw new IllegalStateException("Editor returned an invalid edit.");
            String before=Objects.toString(edit.get("before"),""),after=Objects.toString(edit.get("after"),"");
            int at=original.indexOf(before);boolean valid=!before.isBlank() && before.length()<=600 && !after.isBlank() && after.length()<=1200 && at>=0 && original.indexOf(before,at+1)<0;
            if(valid)for(var interval:occupied)if(at<interval[1] && at+before.length()>interval[0])valid=false;
            edit.put("id","edit-"+(++index));edit.put("valid",valid);
            edit.put("validation",valid?"Exact source passage located":"This suggestion cannot be applied automatically: its source passage is missing, duplicated, overlapping or oversized.");
            if(valid)occupied.add(new int[]{at,at+before.length()});
        }
    }
    public record Choice(String id,String after){}
    public synchronized Map<String,Object> approve(long id,List<Choice> choices,String customResume,String userMotivation) {
        var j=load(id);requireStatus(j,"review");
        if(controls.keySet().stream().anyMatch(k->k!=id))throw new ResponseStatusException(HttpStatus.CONFLICT,"Another swarm is running. Retry after it pauses.");
        checkText(userMotivation,"Your motivation answer",600);
        if(choices==null || choices.size()>6)throw new IllegalArgumentException("Choose at most six offered edits.");
        @SuppressWarnings("unchecked") var offered=(List<Map<String,Object>>)((Map<String,Object>)steps(j).get(4).get("result")).get("edits");
        String approved=applyEdits((String)j.get("resume"),offered,choices);
        if(customResume!=null && !customResume.isBlank()){checkText(customResume,"Your edited résumé",12000);approved=customResume.trim();}
        j.put("approvedResume",approved);j.put("approvedEdits",choices);j.put("customResumeUsed",customResume!=null&&!customResume.isBlank());j.put("userMotivation",userMotivation.trim());j.put("approvedAt",Instant.now().toString());j.put("status","finalizing");
        save(id,j);queue(id,true);return get(id);
    }
    static String applyEdits(String original,List<Map<String,Object>> offered,List<Choice> choices) {
        Set<String> ids=new HashSet<>();List<Map.Entry<Integer,Map.Entry<String,String>>> replacements=new ArrayList<>();
        for(var choice:choices){
            if(!ids.add(choice.id()))throw new IllegalArgumentException("An edit was selected twice.");
            var edit=offered.stream().filter(e->choice.id().equals(e.get("id"))).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown edit."));
            if(!Boolean.TRUE.equals(edit.get("valid")))throw new IllegalArgumentException("That suggestion cannot be applied automatically. Use your own résumé text instead.");
            checkText(choice.after(),"Edited passage",1600);String before=(String)edit.get("before");
            replacements.add(Map.entry(original.indexOf(before),Map.entry(before,choice.after())));
        }
        replacements.sort(Map.Entry.<Integer,Map.Entry<String,String>>comparingByKey().reversed());StringBuilder out=new StringBuilder(original);
        for(var r:replacements)out.replace(r.getKey(),r.getKey()+r.getValue().getKey().length(),r.getValue().getValue());
        if(out.length()>12000)throw new IllegalArgumentException("The approved résumé is too long.");return out.toString();
    }
    public synchronized Map<String,Object> finalizeDocuments(long id,String coverLetter) {
        var j=load(id);requireStatus(j,"final_review");checkText(coverLetter,"Cover letter",6000);
        j.put("coverLetter",coverLetter.trim());j.put("status","completed");j.put("finishedAt",Instant.now().toString());save(id,j);return get(id);
    }
    public synchronized Map<String,Object> cancel(long id) {
        var j=load(id);if(List.of("completed","cancelled","failed","interrupted").contains(j.get("status")))return get(id);
        var control=controls.get(id);if(control!=null)control.set(true);
        j.put("status","cancelled");j.put("error","Cancelled. In-flight calls may still accrue usage; no new stage will start.");
        steps(j).stream().filter(s->List.of("waiting","running").contains(s.get("status"))).forEach(s->s.put("status","cancelled"));save(id,j);return get(id);
    }
    public Map<String,Object> get(long id){var j=load(id);j.put("usage",usage.swarmSnapshot(id));j.put("steps",steps(j).stream().map(this::publicStep).toList());return j;}
    public List<Map<String,Object>> list(){return db.query("SELECT id,status,created_at,detail FROM swarm_runs ORDER BY id DESC LIMIT 30",(rs,n)->{
        @SuppressWarnings("unchecked") var j=json.readValue(rs.getString("detail"),LinkedHashMap.class);return Map.<String,Object>of("id",rs.getLong("id"),"name",j.get("name"),"status",rs.getString("status"),"createdAt",rs.getString("created_at"));});}
    public String document(long id,boolean letter){var j=load(id);requireStatus(j,"completed");return (String)j.get(letter?"coverLetter":"approvedResume");}
    private Map<String,Object> publicStep(Map<String,Object> step){var view=new LinkedHashMap<>(step);view.remove("instructions");return view;}
    private synchronized Map<String,Object> load(long id){var rows=db.query("SELECT detail FROM swarm_runs WHERE id=?",(rs,n)->{
        @SuppressWarnings("unchecked") Map<String,Object> j=json.readValue(rs.getString(1),LinkedHashMap.class);return j;},id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Swarm not found.");return rows.get(0);}
    private synchronized void mutate(long id,Consumer<Map<String,Object>> change){var j=load(id);change.accept(j);save(id,j);}
    private void save(long id,Map<String,Object> j){db.update("UPDATE swarm_runs SET status=?,detail=? WHERE id=?",j.get("status"),json.writeValueAsString(j),id);}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> steps(Map<String,Object> j){return (List<Map<String,Object>>)j.get("steps");}
    private static void requireStatus(Map<String,Object> j,String status){if(!status.equals(j.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"This action requires "+status+"; current state is "+j.get("status")+".");}
    private static void checkText(String text,String label,int max){if(text==null || text.isBlank() || text.length()>max)throw new IllegalArgumentException(label+" must contain 1–"+max+" characters.");}
    @PreDestroy public void close(){controls.values().forEach(c->c.set(true));coordinator.shutdownNow();agents.shutdownNow();}
}

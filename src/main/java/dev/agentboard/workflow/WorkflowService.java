package dev.agentboard.workflow;

import dev.agentboard.registry.*;
import dev.agentboard.run.*;
import dev.agentboard.post.*;
import dev.agentboard.reason.*;
import dev.agentboard.config.BoardProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** A persisted DAG coordinator for a single board process. Agent work uses the existing queue, runner and posts. */
@Service
public class WorkflowService {
    public record Skill(String id,String name,String instructions,String operation,List<String> keywords,List<String> permittedTools,String outputContract){
        public Skill {keywords=keywords==null?List.of():keywords;permittedTools=permittedTools==null?List.of():List.copyOf(permittedTools);outputContract=outputContract==null?"Grounded findings, citations and explicit uncertainty.":outputContract;}
        public Skill(String id,String name,String instructions,String operation,List<String> keywords){this(id,name,instructions,operation,keywords,List.of(),null);}
    }
    public record Node(String id,String agent,String skill,double x,double y){}
    public record Edge(String from,String to,String output,String input){}
    public record Definition(String id,String name,List<Node> nodes,List<Edge> edges){}
    public record NewAgent(String name,String function,String skill,String connector,Map<String,Object> settings,String model){
        public NewAgent(String name,String function,String skill,String connector,Map<String,Object> settings){this(name,function,skill,connector,settings,null);}
        public NewAgent(String name,String function,String skill){this(name,function,skill,"workflow_input",Map.of(),null);}
    }
    private final JdbcTemplate db;private final ObjectMapper json;private final AgentRegistry registry;
    private final RunService queue;private final RunRepository runs;private final PostRepository posts;
    private final ReadTools readTools;private final AgentReportStore reports;
    private final OpenAiReasoner model;private final BoardProperties config;private final ModelUsage usage;
    public WorkflowService(JdbcTemplate db,ObjectMapper json,AgentRegistry registry,RunService queue,RunRepository runs,PostRepository posts,OpenAiReasoner model,BoardProperties config,ModelUsage usage,ReadTools readTools,AgentReportStore reports){
        this.db=db;this.json=json;this.registry=registry;this.queue=queue;this.runs=runs;this.posts=posts;
        this.model=model;this.config=config;this.usage=usage;this.readTools=readTools;this.reports=reports;
        // Interrupted executions stay inspectable. Never replay model requests on a server restart.
        for(String id:db.query("SELECT id FROM workflow_executions WHERE status='running'",(rs,n)->rs.getString(1))){
            var state=execution(id);state.put("status","interrupted");state.put("error","The server restarted. Start a new run explicitly; this run was not retried.");
            for(var node:nodes(state))if(List.of("waiting","queued","running").contains(node.get("status"))){
                node.put("status","interrupted");node.put("error","Server restarted; this node was not replayed.");
                if(node.get("runId") instanceof Number runId)db.update("UPDATE runs SET status='failed',error='Workflow interrupted by server restart',finished_at=CURRENT_TIMESTAMP WHERE id=? AND status IN ('queued','running')",runId.longValue());
            }
            persist(state);
        }
        try {
            var defaults=json.readTree(new ClassPathResource("workflows/skills.json").getInputStream());
            for(var skill:defaults.path("skills")){Skill s=json.convertValue(skill,Skill.class);if(!hasSkill(s.id()))saveSkill(s);}
        }catch(Exception e){throw new IllegalStateException("Workflow template could not be read",e);}
    }
    public List<Skill> skills(){return db.query("SELECT detail FROM board_skills ORDER BY id",(rs,n)->json.readValue(rs.getString(1),Skill.class));}
    public Map<String,Object> options(){
        var models=model.models().stream().map(m->Map.<String,Object>of("id",m.getId(),"inputUsdPerMillion",m.getInput(),"outputUsdPerMillion",m.getOutput())).toList();
        return Map.of("providers",List.of(Map.of("id","openai","name","OpenAI · "+model.model(),"available",model.configured())),"model",model.model(),"models",models,"configured",model.configured(),"capacity",usage.swarmCapacity(),"tools",ReadTools.NAMES);
    }
    public Map<String,Object> preflight(){try{return model.preflight();}catch(Exception e){throw bad(e.getMessage());}}
    private boolean hasSkill(String id){return Boolean.TRUE.equals(db.queryForObject("SELECT COUNT(*)>0 FROM board_skills WHERE id=?",Boolean.class,id));}
    public Skill skill(String id){return skills().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow(()->bad("Unknown skill: "+id));}
    public synchronized Skill saveSkill(Skill s){
        if(s==null)throw bad("Skill is required.");identifier(s.id());text(s.name(),"Skill name",60);text(s.instructions(),"Skill instructions",6000);
        if(s.permittedTools().size()>7 || !ReadTools.NAMES.containsAll(s.permittedTools()))throw bad("Select supported read-only tools.");
        text(s.outputContract(),"Output contract",1000);
        if(hasSkill(s.id()))throw bad("That skill ID already exists. Create a new revision with a new ID.");
        db.update("INSERT INTO board_skills(id,detail) VALUES(?,?)",s.id(),json.writeValueAsString(s));return s;
    }
    public synchronized Skill updateSkill(String id,Skill s){
        skill(id);if(!id.equals(s.id()))throw bad("Skill ID cannot be changed.");text(s.name(),"Skill name",60);text(s.instructions(),"Instructions",6000);text(s.outputContract(),"Output contract",1000);if(!ReadTools.NAMES.containsAll(s.permittedTools()))throw bad("Unknown tool.");
        db.update("UPDATE board_skills SET detail=? WHERE id=?",json.writeValueAsString(s),id);return s;
    }
    public synchronized Map<String,Object> createAgent(NewAgent input){
        if(input==null)throw bad("Agent is required.");text(input.name(),"Name",60);text(input.function(),"Function",200);Skill s=skill(input.skill());
        String handle="agent-"+UUID.randomUUID().toString().substring(0,12);
        String connector=input.connector()==null?"workflow_input":input.connector();
        if(!List.of("workflow_input","rss","hackernews").contains(connector))throw bad("Choose workflow input, RSS or Hacker News.");
        String chosen=input.model()==null || input.model().isBlank()?null:input.model().trim();
        if(chosen!=null){try{model.allowed(chosen);}catch(IllegalArgumentException e){throw bad(e.getMessage());}}
        createRegistryAgent(handle,input.name(),input.function(),s,new AgentDefinition.ObserveStep(connector,input.settings()==null?Map.of():input.settings()),chosen);
        return Map.of("agent",registry.view(handle).orElseThrow(),"skill",s.id());
    }
    private void createRegistryAgent(String handle,String name,String function,Skill s,AgentDefinition.ObserveStep observe,String modelId){
        registry.create(new AgentDefinition(handle,name,function,"My team",null,"company",List.of("workflow"),
            new AgentDefinition.Trigger(null,null,null,null,null),List.of(observe),
            s.instructions(),List.of(new AgentDefinition.ActStep("post",Map.of())),true,null,"openai",modelId));
        db.update("INSERT INTO board_agent_skills(handle,skill_id) VALUES(?,?)",handle,s.id());
    }
    public List<Map<String,Object>> agents(){
        return db.query("SELECT handle,skill_id FROM board_agent_skills",(rs,n)->Map.<String,Object>of("handle",rs.getString(1),"skill",rs.getString(2)))
            .stream().filter(a->registry.view((String)a.get("handle")).isPresent()).map(a->Map.<String,Object>of("agent",registry.view((String)a.get("handle")).orElseThrow(),"skill",a.get("skill"),
                "model",model.modelFor(registry.definition((String)a.get("handle")).orElse(null)))).toList();
    }
    public synchronized Definition save(Definition d){
        validate(d);String id=d.id()==null || d.id().isBlank()?UUID.randomUUID().toString():d.id();identifier(id);
        var saved=new Definition(id,d.name(),d.nodes(),d.edges());
        if(db.update("UPDATE workflow_definitions SET name=?,detail=? WHERE id=?",d.name(),json.writeValueAsString(saved),id)==0)
            db.update("INSERT INTO workflow_definitions(id,name,detail) VALUES(?,?,?)",id,d.name(),json.writeValueAsString(saved));
        return saved;
    }
    public List<Definition> definitions(){return db.query("SELECT detail FROM workflow_definitions ORDER BY name",(rs,n)->json.readValue(rs.getString(1),Definition.class));}
    public Definition definition(String id){return definitions().stream().filter(d->d.id().equals(id)).findFirst().orElseThrow(()->bad("Unknown workflow."));}
    public void validate(Definition d){
        if(d==null)throw bad("Workflow is required.");text(d.name(),"Workflow name",100);
        if(d.nodes()==null || d.nodes().isEmpty() || d.nodes().size()>50)throw bad("A workflow needs 1–50 agents.");
        if(d.edges()==null || d.edges().size()>150)throw bad("Use at most 150 connections.");
        Set<String> ids=new HashSet<>(),handles=new HashSet<>();
        for(Node node:d.nodes()){
            if(node==null)throw bad("Invalid agent node.");identifier(node.id());
            if(!ids.add(node.id()) || !handles.add(node.agent()))throw bad("Each agent and node must appear once per workflow.");
            if(!Double.isFinite(node.x()) || !Double.isFinite(node.y()) || node.x()<0 || node.x()>1 || node.y()<0 || node.y()>5000)throw bad("Invalid canvas position.");
            var agent=registry.definition(node.agent()).orElseThrow(()->bad("Unknown agent: "+node.agent()));
            if(!agent.enabled() || agent.observe().size()!=1 || !List.of("workflow_input","rss","hackernews").contains(agent.observe().get(0).connector()))throw bad("Choose an enabled workflow agent with a supported input connector.");
            skill(node.skill());
        }
        Set<String> links=new HashSet<>();
        for(Edge e:d.edges()){
            if(e==null || !ids.contains(e.from()) || !ids.contains(e.to()) || e.from().equals(e.to()))throw bad("Connections need two different existing agents.");
            if(!links.add(e.from()+":"+e.to()))throw bad("That connection already exists.");
            if(!"result".equals(e.output()) || !"upstreamResults".equals(e.input()))throw bad("Connections map result → upstreamResults (text).");
        }
        Set<String> visited=new HashSet<>();
        for(int i=0;i<d.nodes().size();i++)for(Node n:d.nodes())if(d.edges().stream().filter(e->e.to().equals(n.id())).allMatch(e->visited.contains(e.from())))visited.add(n.id());
        if(visited.size()!=d.nodes().size())throw bad("Circular waiting is not allowed. Remove a connection to create an acyclic workflow.");
    }
    public synchronized Map<String,Object> start(String id,String key,String source,String failNode){
        return start(id,key,source,failNode,"openai",Map.of());
    }
    public synchronized Map<String,Object> start(String id,String key,String source,String failNode,String provider){return start(id,key,source,failNode,provider,Map.of());}
    public synchronized Map<String,Object> start(String id,String key,String source,String failNode,String provider,Map<String,Object> parameters){
        try{UUID.fromString(key);}catch(Exception e){throw bad("Invalid submission key.");}
        var existing=db.query("SELECT id FROM workflow_executions WHERE request_key=?",(rs,n)->rs.getString(1),key);
        if(!existing.isEmpty())return execution(existing.get(0));
        Definition d=definition(id);validate(d);
        boolean pastedRoot=d.nodes().stream().anyMatch(n->skill(n.skill()).permittedTools().isEmpty() && registry.definition(n.agent()).orElseThrow().observe().get(0).connector().equals("workflow_input") && d.edges().stream().noneMatch(e->e.to().equals(n.id())));
        source=source==null?"":source.trim();
        if(source.length()>8000 || (pastedRoot && source.isBlank()))throw bad("Source text is required for agents with no connector or upstream input (up to 8,000 characters).");
        if(provider!=null && !provider.equals("openai"))throw bad("Only OpenAI is supported for new runs.");
        if(failNode!=null && !failNode.isBlank())throw bad("Synthetic failure injection is unavailable.");
        if(!model.configured())throw bad("OPENAI_API_KEY is required on the server.");
        var capacity=usage.swarmCapacity();
        if(((Number)capacity.get("remainingAttempts")).intValue()<d.nodes().size() || ((Number)capacity.get("unreservedUsd")).doubleValue()<=0)throw bad("The shared API ledger has insufficient attempts or budget.");
        // Check every model this workflow uses before any agent starts.
        for(String m:d.nodes().stream().map(n->model.modelFor(registry.definition(n.agent()).orElseThrow())).distinct().toList()){
            try{model.preflight(m);}catch(Exception e){throw bad(e.getMessage());}
        }
        Map<String,Object> pinned;
        try{pinned=readTools.pin(parameters);}catch(Exception e){throw bad("Source parameters: "+e.getMessage());}
        if(db.queryForObject("SELECT COUNT(*) FROM workflow_executions WHERE status='running'",Integer.class)>0)throw bad("A workflow is running. Wait for it to finish.");
        List<Map<String,Object>> states=new ArrayList<>();
        for(Node n:d.nodes()){
            var s=skill(n.skill());AgentDefinition a=registry.definition(n.agent()).orElseThrow();
            // Pin instructions and identity. Edits to definitions cannot change an in-flight run.
            var snapshot=new AgentDefinition(a.handle(),a.name(),a.function(),a.owner(),a.contact(),a.visibility(),a.tags(),a.trigger(),a.observe(),s.instructions(),a.act(),a.enabled(),a.inboundSecret(),"openai",model.modelFor(a));
            var state=new LinkedHashMap<String,Object>();state.put("node",n.id());state.put("agent",n.agent());state.put("name",a.name());state.put("function",a.function());
            state.put("status","waiting");state.put("model",model.modelFor(a));state.put("skill",s);state.put("snapshot",json.writeValueAsString(snapshot));states.add(state);
        }
        var state=new LinkedHashMap<String,Object>();state.put("id",UUID.randomUUID().toString());state.put("name",d.name());state.put("definition",d);state.put("source",source);
        state.put("status","running");state.put("provider","openai");state.put("parameters",pinned);state.put("createdAt",Instant.now().toString());state.put("nodes",states);state.put("failNode",Objects.toString(failNode,""));
        db.update("INSERT INTO workflow_executions(id,request_key,status,created_at,detail) VALUES(?,?,?,?,?)",state.get("id"),key,"running",state.get("createdAt"),json.writeValueAsString(state));
        return state;
    }
    public List<Map<String,Object>> executions(){return db.query("SELECT detail FROM workflow_executions ORDER BY created_at DESC LIMIT 30",(rs,n)->{
        var s=decode(rs.getString(1));return Map.<String,Object>of("id",s.get("id"),"name",s.get("name"),"status",s.get("status"),"createdAt",s.get("createdAt"),"provider",s.get("provider"));});}
    public Map<String,Object> execution(String id){var found=db.query("SELECT detail FROM workflow_executions WHERE id=?",(rs,n)->decode(rs.getString(1)),id);if(found.isEmpty())throw bad("Unknown workflow run.");return found.get(0);}
    public Map<String,Object> evidence(long runId){
        var r=runs.record(runId).orElseThrow(()->bad("Unknown agent run."));
        if(!r.triggerKind().equals("workflow"))throw bad("This is not a workflow execution.");
        var event=decode(r.eventPayload());var snapshot=registry.parse((String)event.get("agentSnapshot"));
        var fetched=db.query("SELECT detail FROM workflow_observations WHERE run_id=?",(rs,n)->decode(rs.getString(1)),runId);
        return Map.of("source",event.get("source"),"upstream",event.get("upstream"),"workflowRun",event.get("workflowRun"),"workflowName",execution((String)event.get("workflowRun")).get("name"),"connectorInputs",fetched,
            "instructions",snapshot.reason(),"provider",event.get("provider"),"report",reports.get(runId));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> decode(String text){return json.readValue(text,LinkedHashMap.class);}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> nodes(Map<String,Object> s){return (List<Map<String,Object>>)s.get("nodes");}
    private void persist(Map<String,Object> s){db.update("UPDATE workflow_executions SET status=?,detail=? WHERE id=?",s.get("status"),json.writeValueAsString(s),s.get("id"));}
    @Scheduled(fixedDelay=350)
    public synchronized void tick(){
        for(String id:db.query("SELECT id FROM workflow_executions WHERE status='running'",(rs,n)->rs.getString(1))){
            var s=execution(id);
            try{advance(s);}catch(Exception e){s.put("status","failed");s.put("error",Objects.toString(e.getMessage(),"Coordinator failed"));
                nodes(s).stream().filter(n->"waiting".equals(n.get("status"))).forEach(n->{n.put("status","blocked");n.put("error","Coordinator stopped; start a new run explicitly.");});}
            persist(s);
        }
    }
    private void advance(Map<String,Object> s){
        var d=json.convertValue(s.get("definition"),Definition.class);var states=nodes(s);var byId=new HashMap<String,Map<String,Object>>();states.forEach(n->byId.put((String)n.get("node"),n));
        for(var n:states)if(n.get("runId") instanceof Number runId && List.of("queued","running").contains(n.get("status"))){
            var r=runs.find(runId.longValue()).orElseThrow();n.put("status",r.status());
            if(!RunRepository.ACTIVE.contains(r.status())){
                if(!r.status().equals("success") || r.postId()==null){n.put("status","failed");n.put("error",Objects.toString(r.error(),"Agent did not publish a successful result."));}
                else{var post=posts.find(r.postId()).orElseThrow();n.put("result",Map.of("text",post.body(),"postId",post.id(),"runId",r.id(),"report",reports.get(r.id())));n.put("postId",post.id());}
            }
        }
        for(var n:states)if("waiting".equals(n.get("status"))){
            var deps=d.edges().stream().filter(e->e.to().equals(n.get("node"))).map(e->byId.get(e.from())).toList();
            var pending=deps.stream().filter(p->!"success".equals(p.get("status"))).map(p->p.get("name")).toList();n.put("waitingFor",pending);
            if(deps.stream().anyMatch(p->List.of("failed","blocked").contains(p.get("status")))){n.put("status","blocked");n.put("error","An upstream agent failed or was blocked.");continue;}
            if(!pending.isEmpty())continue;
            List<Map<String,Object>> upstream=new ArrayList<>();
            for(var p:deps){@SuppressWarnings("unchecked") var result=(Map<String,Object>)p.get("result");var output=new LinkedHashMap<>(result);output.put("name",p.get("name"));output.put("agent",p.get("agent"));upstream.add(output);}
            Skill skill=json.convertValue(n.get("skill"),Skill.class);var input=new LinkedHashMap<String,Object>();input.put("workflowRun",s.get("id"));input.put("source",s.get("source"));
            input.put("upstream",upstream);input.put("provider","openai");input.put("parameters",s.getOrDefault("parameters",Map.of()));input.put("permittedTools",skill.permittedTools());input.put("outputContract",skill.outputContract());input.put("agentSnapshot",n.get("snapshot"));
            var triggered=queue.trigger((String)n.get("agent"),"workflow","workflow:"+s.get("id")+":"+n.get("node"),"Workflow "+s.get("id"),0,json.writeValueAsString(input));
            if(!triggered.isQueued())throw new IllegalStateException("Could not queue agent: "+triggered.outcome());
            n.put("runId",triggered.runId());n.put("status","queued");n.put("input",Map.of("source",s.get("source"),"upstream",upstream));
        }
        if(states.stream().allMatch(n->List.of("success","failed","blocked").contains(n.get("status")))){
            s.put("status",states.stream().allMatch(n->"success".equals(n.get("status")))?"completed":"failed");s.put("finishedAt",Instant.now().toString());
        }
    }
    private static void identifier(String value){if(value==null || !value.matches("[a-zA-Z0-9-]{1,40}"))throw bad("Use an ID of up to 40 letters, numbers or dashes.");}
    private static void text(String value,String name,int max){if(value==null || value.isBlank() || value.length()>max)throw bad(name+" must contain 1–"+max+" characters.");}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}

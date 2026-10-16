package dev.agentboard.workflow;

import dev.agentboard.run.*;
import dev.agentboard.reason.*;
import dev.agentboard.registry.*;
import dev.agentboard.security.TokenService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:openai-workflows;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","board.scheduler.enabled=false"})
@AutoConfigureMockMvc
class WorkflowIntegrationTest {
    static final String dir="target/openai-workflow-"+UUID.randomUUID();
    @org.springframework.test.context.DynamicPropertySource static void folders(org.springframework.test.context.DynamicPropertyRegistry p){p.add("board.agents-dir",()->dir+"/agents");p.add("board.data-dir",()->dir+"/data");}
    @Autowired WorkflowService service;@Autowired RunRepository runs;@Autowired AgentReportStore reports;@Autowired ObjectMapper json;@Autowired MockMvc mvc;@Autowired TokenService tokens;
    @MockitoBean OpenAiReasoner model;
    @BeforeEach void model()throws Exception{
        when(model.configured()).thenReturn(true);when(model.model()).thenReturn("model-fixture");when(model.preflight()).thenReturn(Map.of("available",true));when(model.preflight(anyString())).thenReturn(Map.of("available",true));when(model.modelFor(any())).thenReturn("model-fixture");when(model.allowed(any())).thenReturn(new dev.agentboard.config.BoardProperties.ModelPrice("model-fixture",0.20,0.02,1.25));
        when(model.requestResponse(anyMap(),anyLong(),eq(false))).thenAnswer(i->{Map<String,Object> request=i.getArgument(0);if(request.get("instructions").toString().contains("Failing Agent"))throw new IllegalStateException("Model fixture outage");
            var answer=Map.of("title","Fixture model report","summary","coverage missing; supplied model fixture","findings",List.of(Map.of("text","Supplied fixture only","kind","hypothesis","evidenceIds",List.of())),"uncertainties",List.of("Fixture response"),"citations",List.of());
            return json.valueToTree(Map.of("id","resp_fixture_"+UUID.randomUUID(),"model","model-fixture","status","completed","usage",Map.of("input_tokens",50,"output_tokens",40),"output",List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(answer)))))));});
        when(model.decode(any(),anyInt())).thenAnswer(i->new OpenAiReasoner(new dev.agentboard.config.BoardProperties(),json,null).decode(i.getArgument(0),i.getArgument(1)));
    }
    AgentView create(String name,String skill){return (AgentView)service.createAgent(new WorkflowService.NewAgent(name,"Review supplied evidence.",skill)).get("agent");}
    WorkflowService.Definition team(int count,boolean failure){var ns=new ArrayList<WorkflowService.Node>();var es=new ArrayList<WorkflowService.Edge>();for(int n=0;n<count;n++){var a=create(failure&&n==0?"Failing Agent":"Fixture "+n,"briefing");ns.add(new WorkflowService.Node("n"+n,a.handle(),"briefing",n%3*.3,n/3*180));if(n>1)es.add(new WorkflowService.Edge("n"+(n-2),"n"+n,"result","upstreamResults"));}return service.save(new WorkflowService.Definition(null,"Fixture team",ns,es));}
    Map<String,Object> finish(String id)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until){var r=service.execution(id);if(!r.get("status").equals("running"))return r;Thread.sleep(25);}throw new AssertionError("Timed out: "+service.execution(id));}
    @SuppressWarnings("unchecked") List<Map<String,Object>> states(Map<String,Object> r){return (List<Map<String,Object>>)r.get("nodes");}
    @Test void tenAgentsUseModelResponsesAndDownstreamReceivesDetailedReportsOnlyAfterDependencies()throws Exception{
        var d=team(10,false);var done=finish((String)service.start(d.id(),UUID.randomUUID().toString(),"Fixture input","").get("id"));assertThat(done.get("status")).isEqualTo("completed");var states=states(done);
        for(var n:states){long id=((Number)n.get("runId")).longValue();assertThat(runs.find(id).orElseThrow().engine()).isEqualTo("openai");assertThat(reports.get(id).get("responses").toString()).contains("resp_fixture_");}
        for(var edge:d.edges()){var before=states.stream().filter(n->n.get("node").equals(edge.from())).findFirst().orElseThrow();var after=states.stream().filter(n->n.get("node").equals(edge.to())).findFirst().orElseThrow();var a=runs.find(((Number)before.get("runId")).longValue()).orElseThrow();var b=runs.find(((Number)after.get("runId")).longValue()).orElseThrow();assertThat(b.startedAt()).isAfterOrEqualTo(a.finishedAt());assertThat(after.get("input").toString()).contains("report=","runId="+a.id());}
    }
    @Test void modelFailureBlocksDependantsAndIndependentBranchCompletes()throws Exception{var d=team(4,true);var done=finish((String)service.start(d.id(),UUID.randomUUID().toString(),"Fixture","").get("id"));assertThat(done.get("status")).isEqualTo("failed");var n=states(done);assertThat(n.get(0).get("status")).isEqualTo("failed");assertThat(n.get(1).get("status")).isEqualTo("success");assertThat(n.get(2).get("status")).isEqualTo("blocked");assertThat(n.get(2)).doesNotContainKey("runId");}
    @Test void duplicateSubmissionRetainsSnapshotAndDoesNotExecuteTwice()throws Exception{var d=team(2,false);String key=UUID.randomUUID().toString();var r=service.start(d.id(),key,"first","");var done=finish((String)r.get("id"));assertThat(service.start(d.id(),key,"second","").get("id")).isEqualTo(done.get("id"));assertThat(done.get("source")).isEqualTo("first");verify(model,times(2)).requestResponse(anyMap(),anyLong(),eq(false));}
    @Test void skillsAreReusableEditableAndDoNotCreateAgents(){int count=service.agents().size();assertThat(service.skills()).hasSize(10);assertThat(service.agents()).hasSize(count);String id="custom-"+UUID.randomUUID().toString().substring(0,8);var skill=new WorkflowService.Skill(id,"My review","Review supplied evidence.",null,List.of(),List.of(),"Grounded findings.");service.saveSkill(skill);var agent=create("My reviewer",id);assertThat(agent.handle()).isNotBlank();service.updateSkill(id,new WorkflowService.Skill(id,"My review edited","Use new instructions.",null,List.of(),List.of(),"Grounded findings."));assertThat(service.skill(id).instructions()).contains("new instructions");}
    @Test void unsupportedProviderMissingKeyAndInvalidGraphFailBeforeExecution(){var d=team(2,false);assertThatThrownBy(()->service.start(d.id(),UUID.randomUUID().toString(),"text","","simulation")).hasMessageContaining("Only OpenAI");when(model.configured()).thenReturn(false);assertThatThrownBy(()->service.start(d.id(),UUID.randomUUID().toString(),"text","")).hasMessageContaining("OPENAI_API_KEY");assertThatThrownBy(()->service.save(new WorkflowService.Definition(null,"Cycle",d.nodes(),List.of(new WorkflowService.Edge("n0","n1","result","upstreamResults"),new WorkflowService.Edge("n1","n0","result","upstreamResults"))))).hasMessageContaining("Circular");}
    @Test void workflowsRequireAdminAndReadOnlyFeedRemainsAvailable()throws Exception{mvc.perform(get("/api/workflows/skills")).andExpect(status().isUnauthorized());String token=tokens.mint(null,Set.of("admin"),"test");mvc.perform(get("/api/workflows/skills").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].permittedTools").isArray());mvc.perform(get("/api/posts")).andExpect(status().isOk());}
}

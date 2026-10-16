package dev.agentboard.reason;
import dev.agentboard.connect.*;
import dev.agentboard.config.SecretResolver;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.run.RunContext;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AgentModelRuntimeTest {
 final ObjectMapper json=new ObjectMapper();final OpenAiReasoner model=mock(OpenAiReasoner.class);final SafeHttp http=mock(SafeHttp.class);final SecretResolver secrets=mock(SecretResolver.class);final AgentReportStore store=mock(AgentReportStore.class);
 final ReadTools tools=new ReadTools(http,secrets,mock(ConnectorRegistry.class));final AgentModelRuntime runtime=new AgentModelRuntime(model,tools,store,json);
 RunContext ctx(){var a=new AgentDefinition("a","A","Review","team",null,"company",List.of(),null,List.of(),"Review issue","".isEmpty()?List.of():null,true);return new RunContext(7,a,"workflow","test",0,Map.of("parameters",Map.of("repository","test/repo","issue",1,"revision","a".repeat(40)),"permittedTools",List.of("read_issue")));}
 Object call(String id){return Map.of("id","fc_"+id,"type","function_call","call_id",id,"name","read_issue","arguments","{}");}
 tools.jackson.databind.JsonNode envelope(List<?> out){return json.valueToTree(Map.of("id","resp_fixture_"+UUID.randomUUID(),"model","fixture","status","completed","usage",Map.of("input_tokens",10,"output_tokens",10),"output",out));}
 @BeforeEach void setup()throws Exception{when(model.configured()).thenReturn(true);when(model.model()).thenReturn("fixture");when(model.modelFor(any())).thenReturn("fixture");when(model.allowed("fixture")).thenReturn(new dev.agentboard.config.BoardProperties.ModelPrice("fixture",0.20,0.02,1.25));when(model.decode(any(),anyInt())).thenAnswer(i->new OpenAiReasoner(new dev.agentboard.config.BoardProperties(),json,null).decode(i.getArgument(0),i.getArgument(1)));when(secrets.resolve("GITHUB_TOKEN")).thenReturn(Optional.empty());when(http.getJson(anyString(),anyMap())).thenReturn(Map.of("title","Issue fixture","body","Repro fixture","state","open"));}
 @Test void toolEvidenceAndResponseReceiptsAreRetained()throws Exception{var answer=Map.of("title","Issue","summary","Issue fixture summary","findings",List.of(Map.of("text","Reproduction described","kind","observed","evidenceIds",List.of("run7-e1"))),"uncertainties",List.of("Not executed"),"citations",List.of(Map.of("evidenceId","run7-e1","startLine",0,"endLine",0)));when(model.requestResponse(anyMap(),eq(7L))).thenReturn(envelope(List.of(call("one"))),envelope(List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(answer)))))));var c=ctx();assertThat(runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c).engine()).isEqualTo("openai");verify(model,times(2)).requestResponse(anyMap(),eq(7L));verify(store,atLeastOnce()).save(eq(7L),argThat(r->"completed".equals(r.get("status"))&&r.get("responses").toString().contains("resp_fixture_")&&r.get("evidence").toString().contains("Repro fixture")));}
 @Test void fourthModelRequestIsTheLastAndFailureKeepsReceipts()throws Exception{when(model.requestResponse(anyMap(),eq(7L))).thenAnswer(i->envelope(List.of(call(UUID.randomUUID().toString()))));var c=ctx();assertThatThrownBy(()->runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c)).hasMessageContaining("four-request");verify(model,times(4)).requestResponse(anyMap(),eq(7L));verify(store,atLeastOnce()).save(eq(7L),argThat(r->"failed".equals(r.get("status"))));}
 @Test void malformedFinalOutputFailsRatherThanPublishing()throws Exception{when(model.requestResponse(anyMap(),eq(7L))).thenReturn(envelope(List.of(Map.of("type","message","content",List.of(Map.of("type","output_text","text","{}"))))));var c=ctx();assertThatThrownBy(()->runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c)).hasMessageContaining("Invalid model report");}
 @Test void toolCallLimitStopsBeforeThirteenthRead()throws Exception{
  var calls=new java.util.ArrayList<Object>();for(int n=0;n<13;n++)calls.add(call("call"+n));when(model.requestResponse(anyMap(),eq(7L))).thenReturn(envelope(calls));var c=ctx();
  assertThatThrownBy(()->runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c)).hasMessageContaining("twelve-tool-call");verify(http,times(12)).getJson(anyString(),anyMap());
 }
 @Test void largerJoinRecordsActualExcerptsAndRetainsFullUpstreamEvidence()throws Exception{
  var upstream=new ArrayList<Map<String,Object>>();for(int n=1;n<=8;n++){var findings=new ArrayList<Object>();for(int f=0;f<8;f++)findings.add(Map.of("text","x".repeat(500),"kind","hypothesis","evidenceIds",List.of()));upstream.add(Map.of("name","Producer "+n,"runId",n,"text","Summary","report",Map.of("summary","summary ".repeat(80),"findings",findings,"uncertainties",List.of("unknown"),"evidence",List.of())));}
  var original=ctx();var c=new RunContext(7,original.agent(),"workflow","fixture",0,Map.of("permittedTools",List.of(),"upstream",upstream));
  when(model.requestResponse(anyMap(),eq(7L))).thenAnswer(i->{Map<String,Object> payload=i.getArgument(0);assertThat(json.writeValueAsString(payload).length()).isLessThan(48000);var messages=(List<?>)payload.get("input");var input=json.readTree(Objects.toString(((Map<?,?>)messages.get(0)).get("content")));assertThat(input.path("contextOmissions").size()).isEqualTo(8);assertThat(input.path("upstream").get(0).path("result").path("findings").size()).isEqualTo(4);return envelope(List.of(Map.of("type","message","phase","final_answer","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(Map.of("title","Review","summary","Bounded fixture","findings",List.of(Map.of("text","Review excerpted","kind","hypothesis","evidenceIds",List.of())),"uncertainties",List.of("Input excerpts"),"citations",List.of())))))));});
  assertThat(runtime.execute(c.agent(),Observation.of("input",List.of(),"","low"),c).engine()).isEqualTo("openai");verify(store,atLeastOnce()).save(eq(7L),argThat(r->"completed".equals(r.get("status"))&&((List<?>)r.get("contextOmissions")).size()==8&&r.containsKey("modelInput")));
 }
}

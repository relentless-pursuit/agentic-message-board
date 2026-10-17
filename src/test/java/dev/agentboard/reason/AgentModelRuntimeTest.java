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
 final ReadTools tools=new ReadTools();final dev.agentboard.mcp.client.McpConnectorService mcp=mock(dev.agentboard.mcp.client.McpConnectorService.class);
 final org.springframework.ai.tool.ToolCallback callback=mock(org.springframework.ai.tool.ToolCallback.class);
 final AgentModelRuntime runtime=new AgentModelRuntime(model,tools,store,json,mcp,new dev.agentboard.run.RunCancellation());
 RunContext ctx(){var a=new AgentDefinition("a","A","Review","team",null,"company",List.of(),null,List.of(),"Review issue","".isEmpty()?List.of():null,true);return new RunContext(7,a,"workflow","test",0,Map.of("parameters",Map.of("repository","test/repo","issue",1,"revision","a".repeat(40)),"permittedTools",List.of("github__read_issue")));}
 org.springframework.ai.chat.messages.AssistantMessage.ToolCall call(String id){return ChatFixtures.call(id,"github__read_issue","{}");}
 org.springframework.ai.chat.model.ChatResponse chat()throws Exception{return model.chat(any(),anyString(),anyInt(),eq(7L),anyInt());}
 @BeforeEach void setup()throws Exception{when(model.configured()).thenReturn(true);when(model.model()).thenReturn("fixture");when(model.modelFor(any())).thenReturn("fixture");when(model.allowed("fixture")).thenReturn(new dev.agentboard.config.BoardProperties.ModelPrice("fixture",0.20,0.02,1.25));when(mcp.callback("github__read_issue")).thenReturn(callback);when(mcp.call(anyString(),anyString())).thenAnswer(i->callback.call(i.<String>getArgument(1)));when(mcp.invalidArguments(anyString(),anyMap())).thenReturn(Optional.empty());when(callback.call(anyString())).thenReturn("{\"title\":\"Issue fixture\",\"body\":\"Repro fixture\"}");
 when(secrets.resolve("GITHUB_TOKEN")).thenReturn(Optional.empty());when(http.getJson(anyString(),anyMap())).thenReturn(Map.of("title","Issue fixture","body","Repro fixture","state","open"));}
 @Test void toolEvidenceAndResponseReceiptsAreRetained()throws Exception{var answer=Map.of("title","Issue","summary","Issue fixture summary","findings",List.of(Map.of("text","Reproduction described","kind","observed","evidenceIds",List.of("run7-e1"))),"uncertainties",List.of("Not executed"),"citations",List.of(Map.of("evidenceId","run7-e1","startLine",0,"endLine",0)));when(chat()).thenReturn(ChatFixtures.toolCalls(List.of(call("one"))),ChatFixtures.answer(json.writeValueAsString(answer)));var c=ctx();assertThat(runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c).engine()).isEqualTo("openai");verify(model,times(2)).chat(any(),anyString(),anyInt(),eq(7L),anyInt());verify(store,atLeastOnce()).save(eq(7L),argThat(r->"completed".equals(r.get("status"))&&r.get("responses").toString().contains("resp_fixture_")&&r.get("evidence").toString().contains("Repro fixture")));}
 @Test void fourthModelRequestIsTheLastAndFailureKeepsReceipts()throws Exception{when(chat()).thenAnswer(i->ChatFixtures.toolCalls(List.of(call(UUID.randomUUID().toString()))));var c=ctx();assertThatThrownBy(()->runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c)).hasMessageContaining("fourth and last");verify(model,times(4)).chat(any(),anyString(),anyInt(),eq(7L),anyInt());verify(store,atLeastOnce()).save(eq(7L),argThat(r->"failed".equals(r.get("status"))));}
 @Test void malformedFinalOutputFailsRatherThanPublishing()throws Exception{when(chat()).thenReturn(ChatFixtures.answer("{}"));var c=ctx();assertThatThrownBy(()->runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c)).hasMessageContaining("Invalid model report");}
 @Test void toolCallLimitStopsBeforeThirteenthRead()throws Exception{
  var calls=new java.util.ArrayList<org.springframework.ai.chat.messages.AssistantMessage.ToolCall>();for(int n=0;n<13;n++)calls.add(call("call"+n));when(chat()).thenReturn(ChatFixtures.toolCalls(calls));var c=ctx();
  assertThatThrownBy(()->runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c)).hasMessageContaining("twelve-tool-call");verify(callback,times(12)).call(anyString());verifyNoInteractions(http);
 }
 @Test void largerJoinRecordsActualExcerptsAndRetainsFullUpstreamEvidence()throws Exception{
  var upstream=new ArrayList<Map<String,Object>>();for(int n=1;n<=8;n++){var findings=new ArrayList<Object>();for(int f=0;f<8;f++)findings.add(Map.of("text","x".repeat(500),"kind","hypothesis","evidenceIds",List.of()));upstream.add(Map.of("name","Producer "+n,"runId",n,"text","Summary","report",Map.of("summary","summary ".repeat(80),"findings",findings,"uncertainties",List.of("unknown"),"evidence",List.of())));}
  var original=ctx();var c=new RunContext(7,original.agent(),"workflow","fixture",0,Map.of("permittedTools",List.of(),"upstream",upstream));
  when(chat()).thenAnswer(i->{org.springframework.ai.chat.prompt.Prompt prompt=i.getArgument(0);int size=i.getArgument(4);assertThat(size).isLessThan(60000);var input=json.readTree(prompt.getInstructions().get(1).getText());assertThat(input.path("contextOmissions").size()).isEqualTo(8);assertThat(input.path("upstream").get(0).path("result").path("findings").size()).isEqualTo(4);return ChatFixtures.answer(json.writeValueAsString(Map.of("title","Review","summary","Bounded fixture","findings",List.of(Map.of("text","Review excerpted","kind","hypothesis","evidenceIds",List.of())),"uncertainties",List.of("Input excerpts"),"citations",List.of())));});
  assertThat(runtime.execute(c.agent(),Observation.of("input",List.of(),"","low"),c).engine()).isEqualTo("openai");verify(store,atLeastOnce()).save(eq(7L),argThat(r->"completed".equals(r.get("status"))&&((List<?>)r.get("contextOmissions")).size()==8&&r.containsKey("modelInput")));
 }
 @Test void missingInformationIsAnHonestModelResultRatherThanInvalidStructure()throws Exception {
  var original=ctx();var c=new RunContext(7,original.agent(),"workflow","test",0,Map.of("source","start","permittedTools",List.of()));
  var answer=Map.of("title","Insufficient input","summary","No upstream findings or issue information were provided.","findings",List.of(),"citations",List.of(),"uncertainties",List.of("Provide an issue URL or connect a source-producing agent."));
  when(chat()).thenReturn(ChatFixtures.answer(json.writeValueAsString(answer)));
  assertThat(runtime.execute(c.agent(),Observation.of("input",List.of(),"","low"),c).engine()).isEqualTo("openai");
  verify(store,atLeastOnce()).save(eq(7L),argThat(r->Boolean.TRUE.equals(r.get("needsInput"))&&"completed".equals(r.get("status"))));
  verify(model,times(1)).chat(any(),anyString(),anyInt(),eq(7L),anyInt());
 }
 @Test void emptyReportMustExplainMissingInformation(){
  var answer=Map.<String,Object>of("title","Empty","summary","No result","findings",List.of(),"citations",List.of(),"uncertainties",List.of());
  assertThatThrownBy(()->AgentModelRuntime.validate(answer,List.of())).hasMessageContaining("must explain");
 }
 @Test void removedBuiltInToolCannotBeExecuted()throws Exception {
  var original=ctx();var c=new RunContext(7,original.agent(),"workflow","test",0,Map.of("permittedTools",List.of("read_issue")));
  assertThatThrownBy(()->runtime.execute(c.agent(),Observation.of("input",List.of(),"","low"),c)).hasMessageContaining("Built-in tools are unavailable");
  verify(model,never()).chat(any(),anyString(),anyInt(),anyLong(),anyInt());verifyNoInteractions(http);
 }
 @Test void retiredOutputContractIsNotInjectedIntoTheModelPrompt()throws Exception {
  var original=ctx();var c=new RunContext(7,original.agent(),"workflow","test",0,Map.of("source","Specific task context","permittedTools",List.of(),"outputContract","OLD_CONTRACT_DO_NOT_USE"));
  when(chat()).thenAnswer(i->{
   org.springframework.ai.chat.prompt.Prompt prompt=i.getArgument(0);
   assertThat(prompt.getSystemMessage().getText()).doesNotContain("Output contract","OLD_CONTRACT_DO_NOT_USE");
   return ChatFixtures.answer(json.writeValueAsString(Map.of("title","Review","summary","Supplied task reviewed","findings",List.of(Map.of("text","Specific task was supplied","kind","hypothesis","evidenceIds",List.of())),"citations",List.of(),"uncertainties",List.of())));
  });
  assertThat(runtime.execute(c.agent(),Observation.of("input",List.of(),"","low"),c).engine()).isEqualTo("openai");
 }
 @Test void outputCutOffAtTheTokenLimitFailsClearly()throws Exception{
  when(chat()).thenReturn(ChatFixtures.response(org.springframework.ai.chat.messages.AssistantMessage.builder().content("{\"title\":").build(),"LENGTH"));var c=ctx();
  assertThatThrownBy(()->runtime.execute(c.agent(),Observation.of("Input",List.of(),"","low"),c)).hasMessageContaining("output limit");
 }
}

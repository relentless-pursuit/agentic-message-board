package dev.agentboard.reason;
import dev.agentboard.connect.Observation;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.run.RunContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PlainListingTest {
 @Test void noKeyFailsWithoutFallback() throws Exception {
  var model=mock(OpenAiReasoner.class);when(model.configured()).thenReturn(false);
  var runtime=new AgentModelRuntime(model,mock(ReadTools.class),mock(AgentReportStore.class),new tools.jackson.databind.ObjectMapper());
  var a=new AgentDefinition("a","A","Review","test",null,"company",List.of(),null,List.of(),"Review",List.of(),true);
  assertThatThrownBy(()->runtime.execute(a,Observation.of("empty",List.of(),"","low"),new RunContext(1,a,"manual","test",0,Map.of()))).hasMessageContaining("OPENAI_API_KEY");
  verify(model,never()).requestResponse(anyMap(),anyLong());
 }
}

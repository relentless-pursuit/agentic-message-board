package dev.agentboard;

import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.registry.AgentRepository;
import dev.agentboard.run.*;
import dev.agentboard.reason.*;
import dev.agentboard.security.TokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:runtime-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "board.agents-dir=target/runtime-test-agents", "board.data-dir=target/runtime-test-data"})
@AutoConfigureMockMvc
class RuntimeIntegrationTest {
    @MockitoBean AgentModelRuntime runtime;
    @BeforeEach void fixture()throws Exception{when(runtime.execute(any(),any(),any())).thenReturn(new Reasoning(true,"Fixture model","Supplied fixture result","low",List.of(),"openai","Test model fixture"));}
    @Autowired RunService runs;
    @Autowired AgentRepository agents;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;

    @Test void tenAgentsExecuteAndRepeatedRunsInvokeModelRuntimeAgain() throws Exception {
        String prefix="runtime-"+System.nanoTime()%100000+"-";
        for(int i=0;i<10;i++){
            var agent=new AgentDefinition(prefix+i,"Runtime Agent "+(i+1),"Report the supplied result concisely.","local-test",null,"company",List.of("test"),
                    new AgentDefinition.Trigger(null),List.of(new AgentDefinition.ObserveStep("board",Map.of("since","1h"))),"State only what the facts say.",List.of(new AgentDefinition.ActStep("post",Map.of())),true);
            agents.upsert(agent,"test",json.writeValueAsString(agent));
        }
        for(int round=0;round<2;round++)
            for(int i=0;i<10;i++)runs.trigger(prefix+i,"test","round:"+round,"test round "+round,0,null);
        await(prefix,20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE agent_handle LIKE ?",Long.class,prefix+"%")).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE agent_handle LIKE ? AND status='success' AND engine='openai'",Long.class,prefix+"%")).isEqualTo(20);
        verify(runtime,times(20)).execute(any(),any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage",Long.class)).isZero();
    }
    @Test void removedAdminEndpointsAreNotServed() throws Exception {
        mvc.perform(post("/api/benchmarks").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        String admin=tokens.mint(null,Set.of("admin"),"runtime-test");
        for(String path:List.of("/api/status","/api/queue","/api/audit","/api/tokens","/api/model-usage","/api/owners","/api/registry/errors"))
            mvc.perform(get(path).header("Authorization","Bearer "+admin)).andExpect(status().isNotFound());
        mvc.perform(post("/api/benchmarks").header("Authorization","Bearer "+admin).contentType("application/json").content("{}")).andExpect(status().isNotFound());
    }
    private void await(String prefix,long total) throws Exception {
        long until=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<until) {
            Long done=jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE agent_handle LIKE ? AND status NOT IN ('queued','running')",Long.class,prefix+"%");
            if(done==total) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Runs did not finish");
    }
}

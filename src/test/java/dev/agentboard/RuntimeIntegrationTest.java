package dev.agentboard;

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
import java.util.Set;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:runtime-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "board.agents-dir=target/runtime-test-agents", "board.data-dir=target/runtime-test-data", "board.benchmark.enabled=true"})
@AutoConfigureMockMvc
class RuntimeIntegrationTest {
    @MockitoBean AgentModelRuntime runtime;
    @BeforeEach void fixture()throws Exception{when(runtime.execute(any(),any(),any())).thenReturn(new Reasoning(true,"Fixture model","Supplied fixture result","low",java.util.List.of(),"openai","Test model fixture"));}
    @Autowired BenchmarkService benchmark;
    @Autowired RunService runs;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Autowired ModelUsage usage;
    @Autowired JdbcTemplate jdbc;
    @Autowired TokenService tokens;
    @Autowired MockMvc mvc;

    @Test void tenAgentsExecuteAndRepeatedRunsInvokeModelRuntimeAgain() throws Exception {
        var batch=benchmark.start(10,true);String id=(String)batch.get("batch");
        await(id,10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE agent_handle LIKE ?",Long.class,"bench-"+id+"-%")).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE agent_handle LIKE ? AND status='success' AND engine='openai'",Long.class,"bench-"+id+"-%")).isEqualTo(10);
        benchmark.submit(id,true,1);await(id,20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE agent_handle LIKE ? AND engine='openai'",Long.class,"bench-"+id+"-%")).isEqualTo(20);
        verify(runtime,times(20)).execute(any(),any(),any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_usage",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM run_steps WHERE run_id IN (SELECT id FROM runs WHERE agent_handle LIKE ?)",Long.class,"bench-"+id+"-%")).isEqualTo(60);
    }
    @Test void securityProtectsWritesAndPilotEndpoints() throws Exception {
        mvc.perform(post("/api/benchmarks").contentType("application/json").content("{\"agents\":1,\"live\":false}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/model-usage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/skill-jobs/options")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/skill-jobs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/skill-jobs/example/artifacts/0")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/swarm")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/swarm/options")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/swarm/1/documents/resume.docx")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/swarm/1/approve").contentType("application/json").content("{\"choices\":[]}")).andExpect(status().isUnauthorized());
        String admin=tokens.mint(null,Set.of("admin"),"runtime-test");
        mvc.perform(get("/api/model-usage").header("Authorization","Bearer "+admin)).andExpect(status().isOk());
        mvc.perform(get("/api/swarm/options").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.agents.length()").value(10));
        mvc.perform(multipart("/api/swarm/source").param("url","http://127.0.0.1/private").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/benchmarks").header("Authorization","Bearer "+admin).contentType("application/json").content("{\"agents\":11,\"live\":false}"))
                .andExpect(status().isBadRequest());
    }
    @Test void changedFactsWithSameEventIdAreNotSuppressed() throws Exception {
        String id=(String)benchmark.start(1,true).get("batch");await(id,1);
        String handle=benchmark.handles(id).get(0);
        runs.trigger(handle,"bench-local","changed:"+id,"changed test facts",0,
                json.writeValueAsString(java.util.Map.of("event","pilot.manual","id",handle,"status","SUCCESS","summary","Synthetic CI recovered")));
        await(id,2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE agent_handle=?",Long.class,handle)).isEqualTo(2);
    }
    private void await(String id,long total) throws Exception {
        long until=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<until) {
            Long done=jdbc.queryForObject("SELECT COUNT(*) FROM runs WHERE agent_handle LIKE ? AND status NOT IN ('queued','running')",Long.class,"bench-"+id+"-%");
            if(done==total) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Pilot did not finish: "+benchmark.status(id));
    }
}

package dev.agentboard.post;

import dev.agentboard.run.RunRepository;
import dev.agentboard.security.TokenService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:feed-history-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "board.agents-dir=target/empty-feed-test-agents","board.data-dir=target/feed-history-test-data","board.scheduler.enabled=false"})
@AutoConfigureMockMvc
class FeedHistoryTest {
    @Autowired FeedHistoryService history;@Autowired PostRepository posts;@Autowired RunRepository runs;
    @Autowired JdbcTemplate db;@Autowired MockMvc mvc;@Autowired TokenService tokens;@Autowired ObjectMapper json;
    @BeforeEach void clean(){db.update("DELETE FROM posts");db.update("DELETE FROM runs");}
    long addPost(String agent,Long run,Instant when){return posts.insert(agent,run,new PostDraft("update","low","Supplied evidence","Coverage evidence",List.of("simulation"),List.of(),null),"agent",0,when);}
    long count(){return posts.count(new PostRepository.PostQuery(null,null,null,null,null,false));}
    Map<String,Object> clear(String range,String scope){var p=history.preview(range,scope);return history.clear(range,scope,(String)p.get("through"),true,"test-admin");}
    @Test void rangesClearOnlyMatchingTimesAndKeepExecutionEvidence()throws Exception{
        Instant now=Instant.now();var ids=List.of(addPost("fixture",null,now.minusSeconds(1800)),addPost("fixture",null,now.minusSeconds(7200)),addPost("fixture",null,now.minus(Duration.ofDays(2))),addPost("fixture",null,now.minus(Duration.ofDays(8))));
        assertThat(history.preview("hour","all").get("count")).isEqualTo(1L);
        assertThat(clear("hour","all").get("cleared")).isEqualTo(1);assertThat(count()).isEqualTo(3);
        assertThat(clear("day","all").get("cleared")).isEqualTo(1);assertThat(count()).isEqualTo(2);
        assertThat(clear("week","all").get("cleared")).isEqualTo(1);assertThat(count()).isEqualTo(1);
        assertThat(clear("all","all").get("cleared")).isEqualTo(1);assertThat(count()).isZero();
        for(long id:ids)assertThat(posts.find(id)).isPresent();
        assertThat(posts.since(Instant.EPOCH,100)).isEmpty();assertThat(posts.statsSince(Instant.EPOCH)).isEmpty();
        mvc.perform(get("/api/posts/"+ids.get(0))).andExpect(status().isOk());
    }
    @Test void previewBoundaryPreservesNewPostsAndRepeatedClearIsIdempotent(){
        addPost("fixture",null,Instant.now().minusSeconds(60));var p=history.preview("all","all");String through=(String)p.get("through");
        long later=addPost("fixture",null,Instant.parse(through).plusSeconds(1));
        assertThat(history.clear("all","all",through,true,"test-admin").get("cleared")).isEqualTo(1);
        assertThat(history.clear("all","all",through,true,"test-admin").get("cleared")).isEqualTo(0);
        assertThat(count()).isEqualTo(1);assertThat(posts.search(new PostRepository.PostQuery(null,null,null,null,null,false),10,0)).extracting(PostView::id).containsExactly(later);
    }
    @Test void sampleCleanupKeepsUserSimulationAndNormalPosts(){
        long demo=runs.create("demo-build","workflow",UUID.randomUUID().toString(),"Sample",0,"{}").orElseThrow();runs.finish(demo,"success","Sample",null,"simulation",null,null);
        long user=runs.create("agent-user","workflow",UUID.randomUUID().toString(),"User input",0,"{}").orElseThrow();runs.finish(user,"success","User input",null,"simulation",null,null);
        addPost("demo-build",demo,Instant.now().minusSeconds(1));addPost("agent-user",user,Instant.now().minusSeconds(1));addPost("normal",null,Instant.now().minusSeconds(1));
        assertThat(clear("all","demo").get("cleared")).isEqualTo(1);assertThat(count()).isEqualTo(2);assertThat(runs.find(demo)).isPresent();
    }
    @Test void clearingRequiresAdminConfirmationAndValidRangeAndWritesAudit()throws Exception{
        addPost("fixture",null,Instant.now().minusSeconds(10));
        mvc.perform(get("/api/feed/clear-preview?range=day")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/feed/clear").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        String scoped=tokens.mint("fixture",Set.of("post"),"post-only");
        mvc.perform(get("/api/feed/clear-preview?range=day").header("Authorization","Bearer "+scoped)).andExpect(status().isForbidden());
        String admin=tokens.mint(null,Set.of("admin"),"clear-test");var p=history.preview("day","all");
        mvc.perform(post("/api/feed/clear").header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(Map.of("range","day","through",p.get("through"),"confirmed",false)))).andExpect(status().isBadRequest());
        assertThatThrownBy(()->history.preview("year","all")).hasMessageContaining("Choose");
        assertThatThrownBy(()->history.clear("all","all",Instant.now().minusSeconds(1000).toString(),true,"test")).hasMessageContaining("expired");
        assertThat(count()).isEqualTo(1);
        mvc.perform(post("/api/feed/clear").header("Authorization","Bearer "+admin).contentType("application/json").content(json.writeValueAsString(Map.of("range","day","through",p.get("through"),"confirmed",true)))).andExpect(status().isOk()).andExpect(jsonPath("$.cleared").value(1));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='feed.cleared'",Long.class)).isPositive();
    }
}

package dev.agentboard;

import dev.agentboard.security.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Set;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:empty-board;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "board.agents-dir=target/empty-agent-registry","board.data-dir=target/empty-board-data"})
@AutoConfigureMockMvc
class EmptyLocalBoardTest {
    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Test void emptyBoardDoesNotOfferSyntheticAgents() throws Exception {
        mvc.perform(get("/api/agents")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/connectors")).andExpect(status().isOk()).andExpect(jsonPath("$.connectors.length()").value(8))
                .andExpect(jsonPath("$.connectors[?(@.name == 'http_check')].fields[0].key").value("urls"));
        String token=tokens.mint(null,Set.of("admin"),"empty-board-test");
        mvc.perform(get("/api/agents")).andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/agents").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"handle\":\"invalid-connector\",\"name\":\"Bad connector\",\"function\":\"Monitor a source\",\"owner\":\"test\",\"trigger\":{\"every\":\"30m\"},\"observe\":[{\"connector\":\"xyz\",\"with\":{}}],\"act\":[{\"action\":\"post\",\"with\":{}}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/agents")).andExpect(jsonPath("$.total").value(0));
    }
}

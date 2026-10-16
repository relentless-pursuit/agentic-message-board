package dev.agentboard.reason;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.swarm.SwarmSkills;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class OpenAiCompletionTest {
    final ObjectMapper json=new ObjectMapper();
    final OpenAiReasoner client=new OpenAiReasoner(new BoardProperties(),json,null);
    @Test void incompleteOutputPreservesProviderReasonUsageAndDiscardsPartialJson(){
        var response=json.readTree("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"usage\":{\"input_tokens\":908,\"output_tokens\":900},\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{truncated\"}]}]}");
        var failure=catchThrowableOfType(()->client.decode(response,900),OpenAiReasoner.ResponseProblem.class);
        assertThat(failure.reason).isEqualTo("max_output_tokens");assertThat(failure.getMessage()).contains("900/900 tokens","Partial JSON was discarded");
        assertThat(failure.usage).containsEntry("outputTokens",900L).containsEntry("inputTokens",908L);
    }
    @Test void contentFilterIsNotMisreportedAsAnOutputLimit(){
        var response=json.readTree("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"},\"usage\":{\"output_tokens\":12}}");
        assertThatThrownBy(()->client.decode(response,900)).hasMessageContaining("content_filter").hasMessageNotContaining("output limit");
    }
    @Test void completedCompactJsonIsParsedNormally(){
        var response=json.valueToTree(Map.of("status","completed","usage",Map.of("input_tokens",100,"output_tokens",50),"output",java.util.List.of(Map.of("content",java.util.List.of(Map.of("type","output_text","text","{\"summary\":\"done\",\"items\":[]}"))))));
        assertThat(client.decode(response,1600).answer().path("summary").asText()).isEqualTo("done");
    }
    @Test void analysisSchemasHaveBoundedTextAndExcludeUnusedDraftFields(){
        @SuppressWarnings("unchecked") var properties=(Map<String,Object>)SwarmSkills.schema(2).get("properties");
        assertThat(properties).containsOnlyKeys("summary","items");assertThat(properties.get("summary").toString()).contains("maxLength=300");
        @SuppressWarnings("unchecked") var editor=(Map<String,Object>)SwarmSkills.schema(5).get("properties");assertThat(editor).containsKey("edits").doesNotContainKey("document");
        assertThat(SwarmSkills.AGENTS.get(1).outputTokens()).isGreaterThan(900);
    }
    @org.junit.jupiter.api.Test void parsesFinalAnswerWithoutConcatenatingIntermediateCommentary(){
        var envelope=json.valueToTree(java.util.Map.of("status","completed","usage",java.util.Map.of(),"output",java.util.List.of(
            java.util.Map.of("type","message","phase","commentary","content",java.util.List.of(java.util.Map.of("type","output_text","text","{\"intermediate\":true}"))),
            java.util.Map.of("type","message","phase","final_answer","content",java.util.List.of(java.util.Map.of("type","output_text","text","{\"summary\":\"final\"}"))))));
        org.assertj.core.api.Assertions.assertThat(client.decode(envelope,4000).answer().path("summary").asText()).isEqualTo("final");
    }
}

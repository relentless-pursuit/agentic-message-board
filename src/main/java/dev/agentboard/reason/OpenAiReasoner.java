package dev.agentboard.reason;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.connect.Observation;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Budgeted Responses API transport. AgentModelRuntime owns the bounded read-tool loop. */
@Component
public class OpenAiReasoner {
    private final BoardProperties.Openai config;
    private final ObjectMapper json;
    private final ModelUsage usage;
    private final Semaphore slots;
    private final HttpClient client;
    private long nextRequest;
    public OpenAiReasoner(BoardProperties props,ObjectMapper json,ModelUsage usage) {
        config=props.getOpenai(); this.json=json; this.usage=usage;
        slots=new Semaphore(Math.max(1,config.getMaxConcurrent()),true);
        client=HttpClient.newBuilder().connectTimeout(config.getTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    String key() {
        String value=System.getenv("OPENAI_API_KEY");
        if(value==null || value.isBlank()) {
            try { value=Files.readString(Path.of(config.getKeyFile())).trim(); }
            catch(java.io.IOException e) { value=""; }
        }
        return value.trim();
    }
    public boolean configured() { return !key().isBlank(); }
    private synchronized void pace() throws InterruptedException {
        long wait=nextRequest-System.currentTimeMillis();
        if(wait>0) Thread.sleep(wait);
        nextRequest=System.currentTimeMillis()+config.getRequestSpacing().toMillis();
    }
    public Completion structured(long swarmId,String instructions,String input,Map<String,Object> schema,int outputTokens) throws Exception {
        if(input.length()>30000 || instructions.length()>12000 || outputTokens<1 || outputTokens>2200)
            throw new IllegalArgumentException("Swarm input or output limit exceeded");
        return complete(Map.of("model",config.getModel(),"instructions",instructions,"input",input,"store",false,
                "reasoning",Map.of("effort","none"),"max_output_tokens",outputTokens,
                "text",Map.of("format",Map.of("type","json_schema","name","swarm_step","strict",true,"schema",schema))),swarmId,true);
    }
    private Completion complete(Map<String,Object> payload,long runId,boolean swarm) throws Exception {
        return decode(requestResponse(payload,runId,swarm),((Number)payload.get("max_output_tokens")).intValue());
    }
    public Map<String,Object> preflight() throws Exception { return preflight(model()); }
    /** Checks the key and that the account can use this model, without spending tokens. */
    public Map<String,Object> preflight(String modelId) throws Exception {
        if(!configured())throw new IllegalStateException("OPENAI_API_KEY is required on the server.");
        allowed(modelId);
        var request=HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models/"+java.net.URLEncoder.encode(modelId,StandardCharsets.UTF_8))).timeout(config.getTimeout()).header("Authorization","Bearer "+key()).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()!=200)throw new IllegalStateException("OpenAI model preflight failed for "+modelId+" (HTTP "+response.statusCode()+"). Check OPENAI_API_KEY and that your account can use this model. No model was substituted.");
        return Map.of("available",true,"model",modelId,"checkedAt",java.time.Instant.now().toString());
    }
    public tools.jackson.databind.JsonNode requestResponse(Map<String,Object> payload,long runId,boolean swarm) throws Exception {
        String secret=key();
        if(secret.isBlank())throw new IllegalStateException("OPENAI_API_KEY is missing; set it in the environment or private key file");
        if(!slots.tryAcquire(config.getTimeout().toMillis(),TimeUnit.MILLISECONDS))throw new IllegalStateException("OpenAI slots busy");
        long reservation=0;
        try {
            pace();byte[] body=json.writeValueAsBytes(payload);
            String modelId=String.valueOf(payload.get("model"));var price=allowed(modelId);
            double estimate=price.cost(body.length*2L+4096,0,((Number)payload.get("max_output_tokens")).intValue());
            reservation=swarm?usage.reserveSwarm(runId,modelId,estimate):usage.reserve(runId,modelId,estimate);
            var request=HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                    .timeout(config.getTimeout()).header("Authorization","Bearer "+secret).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] bytes;
            try(var stream=response.body()){bytes=stream.readNBytes(1_000_001);}
            if(bytes.length>1_000_000)throw new IllegalStateException("OpenAI response too large");
            var envelope=json.readTree(bytes);
            if(response.statusCode()/100!=2)throw new IllegalStateException("OpenAI HTTP "+response.statusCode()+" ("+envelope.path("error").path("code").asText("request_failed")+"). Check the configured model and API credentials; no fallback was used.");
            var tokens=envelope.path("usage");usage.success(reservation,tokens.path("input_tokens").asLong(),tokens.path("input_tokens_details").path("cached_tokens").asLong(),tokens.path("output_tokens").asLong());
            usage.response(reservation,envelope.path("id").asText());
            return envelope;
        }catch(Exception e){if(reservation>0)usage.error(reservation);throw e;}
        finally{slots.release();}
    }
    public Completion decode(tools.jackson.databind.JsonNode envelope,int limit) {
        var tokens=envelope.path("usage");long in=tokens.path("input_tokens").asLong(),cached=tokens.path("input_tokens_details").path("cached_tokens").asLong(),out=tokens.path("output_tokens").asLong();
        Map<String,Object> totals=Map.of("inputTokens",in,"cachedInputTokens",cached,"outputTokens",out,"estimatedUsd",priceFor(envelope.path("model").asText(model())).cost(in,cached,out));
        if(!"completed".equals(envelope.path("status").asText())) {
            String reason=envelope.path("incomplete_details").path("reason").asText("not_reported");
            String message=reason.equals("max_output_tokens")?"OpenAI stopped at the output limit ("+out+"/"+limit+" tokens). Partial JSON was discarded; later agents did not start.":"OpenAI response "+envelope.path("status").asText()+" (reason: "+reason+"). Partial output was discarded.";
            throw new ResponseProblem(message,reason,totals);
        }
        StringBuilder output=new StringBuilder();boolean hasFinal=false;
        for(var item:envelope.path("output"))if("final_answer".equals(item.path("phase").asText()))hasFinal=true;
        for(var item:envelope.path("output")){
            String phase=item.path("phase").asText();
            if("commentary".equals(phase) || (hasFinal && !"final_answer".equals(phase)))continue;
            for(var content:item.path("content")) {
                if("refusal".equals(content.path("type").asText()))throw new ResponseProblem("OpenAI refused the request.","refusal",totals);
                if("output_text".equals(content.path("type").asText()))output.append(content.path("text").asText());
            }
        }
        return new Completion(json.readTree(output.toString()),totals);
    }
    public static class ResponseProblem extends IllegalStateException {
        public final String reason;public final Map<String,Object> usage;
        public ResponseProblem(String message,String reason,Map<String,Object> usage){super(message);this.reason=reason;this.usage=usage;}
    }
    /** The board's default model. */
    public String model(){return config.getModel();}
    /** The model an agent runs with: its own choice if it made one, otherwise the default. */
    public String modelFor(AgentDefinition agent){return agent==null || agent.model()==null?config.getModel():agent.model();}
    /** Models agents may choose, with prices. */
    public List<BoardProperties.ModelPrice> models(){return List.copyOf(config.getModels());}
    /** Price of a model that agents may use; anything else is refused before a request is sent. */
    public BoardProperties.ModelPrice allowed(String modelId){
        return config.price(modelId).orElseThrow(()->new IllegalArgumentException("Model "+modelId+" is not allowed. Allowed models: "+config.getModels().stream().map(BoardProperties.ModelPrice::getId).toList()+" (board.openai.models)."));
    }
    /** Price for a model name in a response, which may carry a date suffix such as gpt-5.4-mini-2026-03-17. */
    public BoardProperties.ModelPrice priceFor(String responseModel){
        return config.getModels().stream().filter(m->responseModel.equals(m.getId()) || responseModel.startsWith(m.getId()+"-"))
                .max(Comparator.comparingInt(m->m.getId().length())).orElseGet(()->allowed(config.getModel()));
    }
    public record Completion(tools.jackson.databind.JsonNode answer,Map<String,Object> usage){}
    private static String cut(String s,int n) { return s==null?"":s.substring(0,Math.min(s.length(),n)); }
}

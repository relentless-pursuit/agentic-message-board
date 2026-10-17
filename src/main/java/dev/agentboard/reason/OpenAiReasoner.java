package dev.agentboard.reason;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.registry.AgentDefinition;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Budgeted OpenAI access through Spring AI, plus the model list and prices. AgentModelRuntime owns the tool loop. */
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
    /** Checks the key and that the account can use this model, without spending tokens. */
    public Map<String,Object> preflight(String modelId) throws Exception {
        if(!configured())throw new IllegalStateException("OPENAI_API_KEY is required on the server.");
        allowed(modelId);
        var request=HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models/"+java.net.URLEncoder.encode(modelId,StandardCharsets.UTF_8))).timeout(config.getTimeout()).header("Authorization","Bearer "+key()).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.discarding());
        if(response.statusCode()!=200)throw new IllegalStateException("OpenAI model preflight failed for "+modelId+" (HTTP "+response.statusCode()+"). Check OPENAI_API_KEY and that your account can use this model. No model was substituted.");
        return Map.of("available",true,"model",modelId,"checkedAt",java.time.Instant.now().toString());
    }
    private volatile OpenAiChatModel chatModel;
    private volatile String chatModelKey;
    /** Spring AI's OpenAI chat model, rebuilt only when the key changes. It never runs tools itself; AgentModelRuntime does. */
    private synchronized OpenAiChatModel chatModel(String secret){
        if(chatModel==null || !secret.equals(chatModelKey)){
            // No client retries: every request reserves budget first, so a silent retry could spend twice.
            chatModel=OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey(secret).timeout(config.getTimeout()).maxRetries(0).build()).build();
            chatModelKey=secret;
        }
        return chatModel;
    }
    /**
     * One budgeted model request through Spring AI. Budget is reserved from a size estimate before the call,
     * then settled with the token counts OpenAI reports. Concurrency and spacing limits apply to every request.
     */
    public ChatResponse chat(Prompt prompt,String modelId,int maxOutputTokens,long runId,int promptChars) throws Exception {
        String secret=key();
        if(secret.isBlank())throw new IllegalStateException("OPENAI_API_KEY is missing; set it in .env and restart the board.");
        if(!slots.tryAcquire(config.getTimeout().toMillis(),TimeUnit.MILLISECONDS))throw new IllegalStateException("OpenAI slots busy");
        long reservation=0;
        try {
            pace();
            var price=allowed(modelId);
            reservation=usage.reserve(runId,modelId,price.cost(promptChars*2L+4096,0,maxOutputTokens));
            ChatResponse response=chatModel(secret).call(prompt);
            var tokens=response.getMetadata().getUsage();
            long in=tokens.getPromptTokens()==null?0:tokens.getPromptTokens(),out=tokens.getCompletionTokens()==null?0:tokens.getCompletionTokens();
            long cached=tokens.getCacheReadInputTokens()==null?0:tokens.getCacheReadInputTokens();
            usage.success(reservation,in,cached,out);
            usage.response(reservation,Objects.toString(response.getMetadata().getId(),""));
            return response;
        }catch(Exception e){
            if(reservation>0)usage.error(reservation);
            throw new IllegalStateException("OpenAI request failed: "+Objects.toString(e.getMessage(),e.getClass().getSimpleName())+". Check the model and OPENAI_API_KEY; no other model was used.",e);
        }
        finally{slots.release();}
    }
    /** The model that drafts new agents: board.openai.setup-model when it is priced and on the account, else the default. */
    public String setupModel(){
        String m=config.getSetupModel();
        if(m==null||m.isBlank())return model();
        try{allowed(m);}catch(RuntimeException e){return model();}
        return models().stream().anyMatch(c->c.id().equals(m)&&c.onAccount())?m:model();
    }
    /** The board's default model. */
    public String model(){return config.getModel();}
    /** The model an agent runs with: its own choice if it made one, otherwise the default. */
    public String modelFor(AgentDefinition agent){return agent==null || agent.model()==null?config.getModel():agent.model();}
    /** A model the user may pick, with the price the budget guard uses and whether that price is listed or only an estimate. */
    public record ModelChoice(String id,double inputUsdPerMillion,double outputUsdPerMillion,boolean priceListed,boolean onAccount){}
    private volatile List<String> accountModels=List.of();private volatile long accountModelsAt;
    /**
     * The models to offer: the ones this OpenAI account can use when the key works, otherwise the models with a known price.
     * Prices come from board.openai.models; a model without a listed price is priced at board.openai.fallback-price.
     */
    public List<ModelChoice> models(){
        var ids=new LinkedHashSet<String>();var account=accountModelIds();
        if(account.isEmpty())config.getModels().forEach(m->ids.add(m.getId()));else{config.getModels().stream().map(BoardProperties.ModelPrice::getId).filter(account::contains).forEach(ids::add);ids.addAll(account);}
        ids.add(config.getModel());
        var out=new ArrayList<ModelChoice>();
        for(String id:ids){var listed=config.price(id);var p=listed.orElse(config.getFallbackPrice());out.add(new ModelChoice(id,p.getInput(),p.getOutput(),listed.isPresent(),account.contains(id)));}
        out.sort(Comparator.comparing((ModelChoice m)->!m.priceListed()).thenComparing(ModelChoice::id));
        return out;
    }
    /** Chat-capable models on this account (cached for ten minutes). Empty when there is no key or the call fails. */
    private synchronized Set<String> accountModelIds(){
        if(!configured())return Set.of();
        if(System.currentTimeMillis()-accountModelsAt>600_000){
            try{
                var request=HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models")).timeout(config.getTimeout()).header("Authorization","Bearer "+key()).GET().build();
                var response=client.send(request,HttpResponse.BodyHandlers.ofString());
                var found=new ArrayList<String>();
                if(response.statusCode()==200)for(var m:json.readTree(response.body()).path("data")){String id=m.path("id").asText();if(usableForAgents(id))found.add(id);}
                accountModels=found;
            }catch(Exception e){accountModels=List.of();}
            accountModelsAt=System.currentTimeMillis();
        }
        return new LinkedHashSet<>(accountModels);
    }
    static boolean usableForAgents(String id){
        return id.matches("(gpt-|o\\d|chatgpt-)[A-Za-z0-9._:-]*") && !id.matches(".*(audio|realtime|transcribe|tts|image|embedding|moderation|search|instruct|whisper|dall|vision|computer-use|codex|deep-research).*");
    }
    /** Checks a model id and returns the price used to reserve budget for it. Whether the account can use it is checked by preflight. */
    public BoardProperties.ModelPrice allowed(String modelId){
        if(modelId==null || !modelId.matches("[A-Za-z0-9._:-]{1,80}"))throw new IllegalArgumentException("Choose an OpenAI model id such as gpt-5.4-mini.");
        return config.priceOrFallback(modelId);
    }
    /** Price for a model name in a response, which may carry a date suffix such as gpt-5.4-mini-2026-03-17. */
    public BoardProperties.ModelPrice priceFor(String responseModel){return config.priceOrFallback(responseModel);}
    private static String cut(String s,int n) { return s==null?"":s.substring(0,Math.min(s.length(),n)); }
}

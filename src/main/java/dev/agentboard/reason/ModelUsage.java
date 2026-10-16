package dev.agentboard.reason;

import dev.agentboard.config.BoardProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Statement;
import java.util.Map;

/** Persistent attempt/budget accounting. Unknown-outcome requests retain their reservation. */
@Component
public class ModelUsage {
    private final JdbcTemplate jdbc;
    private final BoardProperties.Openai config;
    public ModelUsage(JdbcTemplate jdbc, BoardProperties props) { this.jdbc=jdbc; config=props.getOpenai(); }

    @Transactional
    public long reserve(long runId, String model, double estimate) {
        if (config.price(model).isEmpty()) throw new IllegalArgumentException("The budget guard has no price for model " + model + "; add it to board.openai.models");
        // Row is installed by schema initialization; the conditional UPDATE is atomic across workers/processes.
        int changed=jdbc.update("UPDATE model_budget SET reserved_usd=reserved_usd+?, calls=calls+1 WHERE name='openai' AND reserved_usd+?<=? AND calls<?",
                estimate, estimate, config.getBudgetUsd(), config.getMaxCalls());
        if(changed!=1) throw new IllegalStateException("OpenAI pilot budget or attempt limit reached; review /api/model-usage");
        var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
            var statement=connection.prepareStatement("INSERT INTO model_usage(run_id,model,state,reserved_usd,created_at) VALUES(?,?,'reserved',?,CURRENT_TIMESTAMP)", new String[]{"id"});
            statement.setLong(1,runId); statement.setString(2,model); statement.setDouble(3,estimate); return statement;
        },keys);
        return keys.getKey().longValue();
    }
    public void success(long id,long input,long cached,long output) {
        String model=jdbc.queryForObject("SELECT model FROM model_usage WHERE id=?",String.class,id);
        double cost=config.price(model).orElseGet(()->config.price(config.getModel()).orElseThrow()).cost(input,cached,output);
        jdbc.update("UPDATE model_usage SET state='success',input_tokens=?,cached_input_tokens=?,output_tokens=?,estimated_usd=? WHERE id=?",
                input,cached,output,cost,id);
    }
    public void response(long id,String responseId){jdbc.update("UPDATE model_usage SET response_id=? WHERE id=?",responseId,id);}
    public void error(long id) { jdbc.update("UPDATE model_usage SET state='error' WHERE id=?",id); }
    @Transactional
    public long reserveSwarm(long swarmId,String model,double estimate) {
        // Lock one workflow while parallel agents reserve against its cap.
        jdbc.queryForObject("SELECT id FROM swarm_runs WHERE id=? FOR UPDATE",Long.class,swarmId);
        var totals=jdbc.queryForMap("SELECT COUNT(*) AS calls,COALESCE(SUM(reserved_usd),0) AS reserved FROM model_usage WHERE run_id=?",-swarmId);
        if(((Number)totals.get("calls")).intValue()>=10 || ((Number)totals.get("reserved")).doubleValue()+estimate>0.10)
            throw new IllegalStateException("This swarm reached its 10-call or $0.10 reservation limit. No additional model call was sent.");
        return reserve(-swarmId,model,estimate);
    }
    public Map<String,Object> swarmSnapshot(long id) {
        var s=jdbc.queryForMap("SELECT COUNT(*) AS calls,COALESCE(SUM(input_tokens),0) AS inputs,COALESCE(SUM(cached_input_tokens),0) AS cached,COALESCE(SUM(output_tokens),0) AS outputs,COALESCE(SUM(estimated_usd),0) AS cost,COALESCE(SUM(reserved_usd),0) AS reserved FROM model_usage WHERE run_id=?",-id);
        return Map.of("calls",s.get("calls"),"inputTokens",s.get("inputs"),"cachedInputTokens",s.get("cached"),"outputTokens",s.get("outputs"),"estimatedUsd",s.get("cost"),"reservedUsd",s.get("reserved"),"budgetUsd",0.10);
    }
    public Map<String,Object> swarmCapacity() {
        var budget=jdbc.queryForMap("SELECT reserved_usd,calls FROM model_budget WHERE name='openai'");
        int attempts=Math.max(0,config.getMaxCalls()-((Number)budget.get("calls")).intValue());
        double dollars=Math.max(0,config.getBudgetUsd()-((Number)budget.get("reserved_usd")).doubleValue());
        return Map.of("remainingAttempts",attempts,"unreservedUsd",dollars,"canStart",attempts>=10 && dollars>=0.10);
    }
    public Map<String,Object> snapshot() {
        var result=jdbc.queryForMap("SELECT COUNT(*) AS attempts, COALESCE(SUM(input_tokens),0) AS input_tokens, COALESCE(SUM(cached_input_tokens),0) AS cached_input_tokens, COALESCE(SUM(output_tokens),0) AS output_tokens, COALESCE(SUM(estimated_usd),0) AS estimated_usd, COALESCE(SUM(reserved_usd),0) AS reserved_usd FROM model_usage");
        result.put("budgetUsd",config.getBudgetUsd()); result.put("maxAttempts",config.getMaxCalls());
        result.put("model",config.getModel()); result.put("models",config.getModels().stream().map(BoardProperties.ModelPrice::getId).toList());
        result.put("pricingSource","board.openai.models (USD per million tokens)");
        return result;
    }
}

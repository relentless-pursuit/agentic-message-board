package dev.agentboard.run;

import dev.agentboard.act.ActContext;
import dev.agentboard.act.Action;
import dev.agentboard.act.ActionRegistry;
import dev.agentboard.act.ActionResult;
import dev.agentboard.connect.Connector;
import dev.agentboard.connect.ConnectorRegistry;
import dev.agentboard.connect.Observation;
import dev.agentboard.hook.Hmac;
import dev.agentboard.reason.Reasoning;
import dev.agentboard.reason.ReasoningService;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.registry.AgentRegistry;
import dev.agentboard.registry.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Diagram box "Agent Runner": the same four steps for every agent, so 10 or 10,000 agents use
 * exactly this code. OBSERVE (code calls connectors) -> REASON (OpenAI, or the template) ->
 * ACT (code runs actions by rule) - each step is saved so the run page shows what happened.
 * Screenshot source; excluded pending database, registry and reasoning integration.
 */
@Service
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    private final RunRepository runs;
    private final AgentRegistry registry;
    private final AgentRepository agents;
    private final ConnectorRegistry connectors;
    private final ActionRegistry actions;
    private final ReasoningService reasoning;
    private final ObjectMapper json;
    private final dev.agentboard.workflow.WorkflowObservationStore observations;

    public AgentRunner(RunRepository runs, AgentRegistry registry, AgentRepository agents, ConnectorRegistry connectors,
                       ActionRegistry actions, ReasoningService reasoning, ObjectMapper json,dev.agentboard.workflow.WorkflowObservationStore observations) {
        this.runs = runs;
        this.registry = registry;
        this.agents = agents;
        this.connectors = connectors;
        this.actions = actions;
        this.reasoning = reasoning;
        this.json = json;
        this.observations=observations;
    }

    public void execute(long runId, String worker) {
        if (!runs.claim(runId, worker)) {
            return;
        }
        Optional<RunRepository.RunRecord> record = runs.record(runId);
        if (record.isEmpty()) return;
        RunRepository.RunRecord run = record.get();
        MDC.put("runId", String.valueOf(runId));
        MDC.put("agent", run.agentHandle());
        agents.recordRunStatus(run.agentHandle(), runId, "running", Instant.now());
        Steps steps = new Steps(runId);
        Outcome outcome;
        try {
            outcome = perform(run, steps);
        } catch (Exception e) {
            log.warn("Run #{} of {} failed", runId, run.agentHandle(), e);
            steps.add("error", "run", "failed", e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
            outcome = new Outcome("failed", null, e.getMessage(), null, null, null);
        }
        runs.finish(runId, outcome.status(), outcome.result(), outcome.error(), outcome.engine(), outcome.fingerprint(), outcome.postId());
        agents.recordRunStatus(run.agentHandle(), runId, outcome.status(), Instant.now());
        log.info("Run #{} of {} finished: {} {}", runId, run.agentHandle(), outcome.status(), outcome.result() == null ? "" : outcome.result());
        MDC.clear();
    }

    private Outcome perform(RunRepository.RunRecord run, Steps steps) {
        Map<String,Object> payload=event(run.eventPayload());
        AgentDefinition agent = run.triggerKind().equals("workflow") && payload.get("agentSnapshot") instanceof String snapshot
                ? registry.parse(snapshot) : registry.definition(run.agentHandle())
                .orElseThrow(() -> new IllegalStateException("agent " + run.agentHandle() + " no longer exists"));
        if (!Boolean.TRUE.equals(agent.enabled())) {
            steps.add("observe", "skipped", "skipped", "the agent is disabled, so this run did nothing", 0);
            return new Outcome("failed", "Skipped: agent is disabled", null, "none", null, null);
        }
        RunContext ctx = new RunContext(run.id(), agent, run.triggerKind(), run.triggerDetail(), run.depth(), event(run.eventPayload()));

        // 1. OBSERVE: code fetches facts.
        List<Observation> found = new ArrayList<>();
        int observeFailures = 0;
        for (AgentDefinition.ObserveStep step : agent.observe()) {
            long t0 = System.nanoTime();
            Optional<Connector> connector = connectors.get(step.connector());
            if (connector.isEmpty()) {
                steps.add("observe", step.connector(), "failed", "no connector called " + step.connector() + "; known: " + connectors.names(), 0);
                observeFailures++;
                continue;
            }
            try {
                Observation o = connector.get().fetch(step.with() == null ? Map.of() : step.with(), ctx);
                found.add(o);
                if(run.triggerKind().equals("workflow") && !step.connector().equals("workflow_input"))observations.save(run.id(),step.connector(),step.with()==null?Map.of():step.with(),o);
                steps.add("observe", step.connector(), "ok", o.summary(), elapsed(t0));
            } catch (Exception e) {
                observeFailures++;
                steps.add("observe", step.connector(), "failed", e.getMessage(), elapsed(t0));
            }
        }
        if ((!agent.observe().isEmpty() && found.isEmpty()) || (run.triggerKind().equals("workflow") && observeFailures>0)) {
            return new Outcome("failed", null, "Input collection failed; inspect observe steps for details", null, null, null);
        }
        Observation obs = found.isEmpty() ? new Observation("no observe steps", List.of(), "", "low", false) : Observation.combine(found);
        if(run.triggerKind().equals("workflow") && agent.observe().stream().noneMatch(s->s.connector().equals("workflow_input")) && ctx.event().get("upstream") instanceof List<?> upstream && !upstream.isEmpty()){
            obs=Observation.combine(List.of(obs,new dev.agentboard.workflow.WorkflowInputConnector().fetch(Map.of(),ctx)));
        }
        String fingerprint = Hmac.sha256(obs.stableKey()+"\n"+json.writeValueAsString(agent)+"\n"+reasoning.cacheKey(ctx));

        // 2. REASON: OpenAI (or the template) decides what is worth saying.
        long t0 = System.nanoTime();
        Reasoning r = reasoning.reason(agent, obs, ctx);
        steps.add("reason", r.engine(), "ok", r.note(), elapsed(t0));
        if (!r.post()) {
            return new Outcome(observeFailures > 0 ? "unstable" : "success", "Nothing worth posting", null, r.engine(), fingerprint, null);
        }

        // 3. ACT: code runs each action, deciding by rules.
        ActContext act = new ActContext(ctx, r);
        int actFailures = 0;
        for (AgentDefinition.ActStep step : agent.act()) {
            long a0 = System.nanoTime();
            Optional<Action> action = actions.get(step.action());
            if (action.isEmpty()) {
                steps.add("act", step.action(), "failed", "no action called " + step.action() + "; known: " + actions.names(), 0);
                actFailures++;
                continue;
            }
            ActionResult result;
            try {
                result = action.get().execute(step.with() == null ? Map.of() : step.with(), act);
            } catch (Exception e) {
                result = ActionResult.failed(e.getMessage());
            }
            if (result.status().equals("failed")) actFailures++;
            steps.add("act", step.action(), result.status(), result.detail(), elapsed(a0));
        }
        String status = observeFailures + actFailures > 0 ? "unstable" : "success";
        return new Outcome(status, r.title(), null, r.engine(), fingerprint, act.postId());
    }

    private Map<String, Object> event(String payload) {
        if (payload == null || payload.isBlank()) return Map.of();
        Object parsed = json.readValue(payload, Object.class);
        if (parsed instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) m;
            return map;
        }
        return Map.of("payload", parsed);
    }

    private static long elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private final class Steps {
        private final long runId;
        private int seq;

        Steps(long runId) {
            this.runId = runId;
        }

        void add(String stage, String name, String status, String detail, long ms) {
            runs.addStep(runId, ++seq, stage, name, status, detail, ms);
        }
    }

    private record Outcome(String status, String result, String error, String engine, String fingerprint, Long postId) {
    }
}

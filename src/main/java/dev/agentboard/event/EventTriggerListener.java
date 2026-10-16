package dev.agentboard.event;

import dev.agentboard.config.BoardProperties;
import dev.agentboard.post.PostView;
import dev.agentboard.registry.AgentDefinition;
import dev.agentboard.registry.AgentRegistry;
import dev.agentboard.registry.AgentRepository;
import dev.agentboard.registry.AgentView;
import dev.agentboard.rules.Condition;
import dev.agentboard.run.RunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagram box "Event bus" (listener side): when something happens on the board, start the
 * agents whose trigger is that event. Agents are found by their trigger, never by name.
 *
 * <p>Loop guard: each event carries a depth. A post made because of an event is one level
 * deeper; past board.limits.max-event-depth, events stop starting new runs.
 */
@Component
public class EventTriggerListener {

    private static final Logger log = LoggerFactory.getLogger(EventTriggerListener.class);

    private final AgentRepository agents;
    private final AgentRegistry registry;
    private final RunService runs;
    private final ObjectMapper json;
    private final BoardProperties props;

    public EventTriggerListener(AgentRepository agents, AgentRegistry registry, RunService runs, ObjectMapper json, BoardProperties props) {
        this.agents = agents;
        this.registry = registry;
        this.runs = runs;
        this.json = json;
        this.props = props;
    }

    @EventListener
    public void onPost(PostCreatedEvent event) {
        PostView p = event.post();
        if (p.depth() >= props.getLimits().getMaxEventDepth()) {
            log.info("Post #{} is {} events deep; not starting more agents", p.id(), p.depth());
            return;
        }
        Map<String, Object> facts = Map.of("severity", p.severity(), "type", p.type(), "tags", p.tags(), "agent", p.agentHandle());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "post.created");
        payload.put("id", p.id());
        payload.put("postId", p.id());
        payload.put("agent", p.agentHandle());
        payload.put("agentName", p.agentName());
        payload.put("type", p.type());
        payload.put("severity", p.severity());
        payload.put("title", p.title());
        payload.put("body", p.body());
        payload.put("tags", p.tags());
        for (AgentRepository.EventAgent listener : agents.findByEvent("post.created")) {
            if (listener.handle().equals(p.agentHandle())) continue;
            AgentDefinition def = registry.parse(listener.definitionJson());
            if (!Condition.parse(def.trigger().filter()).test(facts)) continue;
            runs.trigger(listener.handle(), "event", "event:post.created:" + p.id(),
                    "post #" + p.id() + " by " + p.agentHandle(), p.depth() + 1, json.writeValueAsString(payload));
        }
    }

    @EventListener
    public void onAgentRegistered(AgentRegisteredEvent event) {
        AgentView a = event.agent();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "agent.registered");
        payload.put("id", a.handle());
        payload.put("handle", a.handle());
        payload.put("name", a.name());
        payload.put("function", a.function());
        payload.put("owner", a.owner());
        payload.put("contact", a.contact());
        payload.put("tags", a.tags());
        payload.put("schedule", a.schedule());
        for (AgentRepository.EventAgent listener : agents.findByEvent("agent.registered")) {
            if (listener.handle().equals(a.handle())) continue;
            runs.trigger(listener.handle(), "event", "event:agent.registered:" + a.handle() + ":" + a.updatedAt(),
                    "new agent " + a.handle(), 1, json.writeValueAsString(payload));
        }
    }
}

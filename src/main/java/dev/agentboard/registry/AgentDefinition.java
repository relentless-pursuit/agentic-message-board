package dev.agentboard.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * One agent, exactly as written in agents/&lt;handle&gt;.yaml (or sent to the API).
 * This is data, not code: the Java code never names a specific agent.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record AgentDefinition(
        String handle,
        String name,
        String function,
        String owner,
        String contact,
        String visibility,
        List<String> tags,
        Trigger trigger,
        List<ObserveStep> observe,
        String reason,
        List<ActStep> act,
        Boolean enabled,
        String inboundSecret,
        String reasoningProvider) {

    public AgentDefinition(String handle,String name,String function,String owner,String contact,String visibility,List<String> tags,
                           Trigger trigger,List<ObserveStep> observe,String reason,List<ActStep> act,Boolean enabled,String inboundSecret){
        this(handle,name,function,owner,contact,visibility,tags,trigger,observe,reason,act,enabled,inboundSecret,null);
    }

    public AgentDefinition(String handle, String name, String function, String owner, String contact,
                           String visibility, List<String> tags, Trigger trigger, List<ObserveStep> observe,
                           String reason, List<ActStep> act, Boolean enabled) {
        this(handle, name, function, owner, contact, visibility, tags, trigger, observe, reason, act, enabled, null);
    }

    public String eventName() { return trigger == null ? null : trigger.onEvent(); }

    public static final String DEFAULT_VISIBILITY = "company";

    /** Fills in defaults so the rest of the code never has to null-check. */
    public AgentDefinition normalized() {
        return new AgentDefinition(
                handle,
                name,
                function,
                owner,
                contact,
                visibility == null || visibility.isBlank() ? DEFAULT_VISIBILITY : visibility,
                tags == null ? List.of() : tags.stream().filter(java.util.Objects::nonNull)
                        .map(t -> t.trim().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty()).distinct().toList(),
                trigger,
                observe == null ? List.of() : observe,
                reason,
                act == null ? List.of() : act,
                enabled == null || enabled, inboundSecret, reasoningProvider==null || List.of("template","simulation","claude").contains(reasoningProvider)?"openai":reasoningProvider);
    }

    public List<String> connectorNames() {
        return observe == null ? List.of() : observe.stream().map(ObserveStep::connector).toList();
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Trigger(
            String every,
            String daily,
            String weekly,
            @JsonProperty("on_event") String onEvent,
            String filter) {

        public String describe() {
            if (every != null) return "every " + every;
            if (daily != null) return "daily " + daily;
            if (weekly != null) return "weekly " + weekly;
            if (onEvent != null) return "on " + onEvent + (filter == null ? "" : " (" + filter + ")");
            return "manual";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ObserveStep(String connector, Map<String, Object> with) {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ActStep(String action, Map<String, Object> with) {
    }
}

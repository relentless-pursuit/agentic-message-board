package dev.agentboard.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks an agent definition and returns plain-English problems. An empty list means it is valid. */
public final class AgentValidator {

    public static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9-]{2,39}$");
    private static final Set<String> VISIBILITIES = Set.of("company", "team", "private");

    private AgentValidator() {
    }

    public static List<String> validate(AgentDefinition a) {
        List<String> problems = new ArrayList<>();
        if (a == null) return List.of("agent definition is required");
        if (a.handle() == null || !HANDLE.matcher(a.handle()).matches()) {
            problems.add("handle must be 3-40 characters: lowercase letters, numbers and dashes");
        }
        requireText(problems, "name", a.name(), 60);
        requireText(problems, "function", a.function(), 200);
        requireText(problems, "owner", a.owner(), 80);
        if (a.visibility() != null && !VISIBILITIES.contains(a.visibility())) {
            problems.add("visibility must be one of " + VISIBILITIES);
        }
        validateTrigger(problems, a.trigger());
        if(a.reasoningProvider()!=null && !Set.of("openai").contains(a.reasoningProvider()))problems.add("unsupported reasoning provider");
        if(a.model()!=null && !a.model().matches("[A-Za-z0-9._:-]{1,80}"))problems.add("model must be an OpenAI model id such as gpt-5.4-mini");
        if (a.observe() != null && a.observe().stream().anyMatch(s -> s == null || isBlank(s.connector()))) {
            problems.add("every observe step needs a connector");
        }
        if (a.act() == null || a.act().isEmpty()) {
            problems.add("act needs at least one action, for example: post");
        } else if (a.act().stream().anyMatch(s -> s == null || isBlank(s.action()))) {
            problems.add("every act step needs an action");
        }
        return problems;
    }

    private static void validateTrigger(List<String> problems, AgentDefinition.Trigger t) {
        if (t == null) {
            problems.add("trigger is required: use trigger: {} (or on_event: hook.received for a signed webhook)");
        } else if (t.onEvent() != null && !AgentDefinition.Trigger.HOOK_RECEIVED.equals(t.onEvent())) {
            problems.add("trigger.on_event can only be hook.received");
        }
    }

    private static void requireText(List<String> problems, String field, String value, int max) {
        if (isBlank(value)) {
            problems.add(field + " is required");
        } else if (value.length() > max) {
            problems.add(field + " must be at most " + max + " characters");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

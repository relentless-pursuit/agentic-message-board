package dev.agentboard.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Checks an agent definition and returns plain-English problems. An empty list means it is valid. */
public final class AgentValidator {

    public static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9-]{2,39}$");
    private static final Pattern EVERY = Pattern.compile("^[1-9]\\d*[smhd]$");
    private static final Pattern DAILY = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern WEEKLY = Pattern.compile("^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) ([01]\\d|2[0-3]):[0-5]\\d$");
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
            problems.add("trigger is required, for example: trigger: { every: 30m }");
            return;
        }
        long kinds = Stream.of(t.every(), t.daily(), t.weekly(), t.onEvent()).filter(v -> v != null).count();
        if (kinds == 0 && t.filter()==null) return; // Empty trigger is explicitly manual-only.
        if (kinds != 1) {
            problems.add("trigger needs exactly one of: every, daily, weekly, on_event");
            return;
        }
        if (t.every() != null && !EVERY.matcher(t.every()).matches()) {
            problems.add("trigger.every must look like 30s, 5m, 2h or 1d");
        }
        if (t.daily() != null && !DAILY.matcher(t.daily()).matches()) {
            problems.add("trigger.daily must look like 17:00");
        }
        if (t.weekly() != null && !WEEKLY.matcher(t.weekly()).matches()) {
            problems.add("trigger.weekly must look like Mon 08:00");
        }
        if (t.onEvent() != null && t.onEvent().isBlank()) {
            problems.add("trigger.on_event must name an event, for example post.created");
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

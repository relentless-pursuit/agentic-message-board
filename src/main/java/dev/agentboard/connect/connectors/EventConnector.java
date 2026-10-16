package dev.agentboard.connect.connectors;

import dev.agentboard.connect.Connector;
import dev.agentboard.connect.Observation;
import dev.agentboard.connect.With;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The thing that triggered this run: a post, a new agent or a signed webhook payload.
 * No network call: the event is already in the run context.
 * Clipped event-summary expressions and fallback severity were reconstructed from the screenshots.
 */
@Component
public class EventConnector implements Connector {

    private static final Pattern FAILED = Pattern.compile("(?i)fail|error|broken|red");

    @Override
    public String name() {
        return "event";
    }

    @Override
    public Observation fetch(Map<String, Object> with, RunContext context) {
        Map<String, Object> event = context.event();
        if (event.isEmpty()) {
            return new Observation("no event attached to this run", List.of(), "none", "low", false);
        }
        String name = With.text(event, "event");
        List<String> facts = new ArrayList<>();
        flatten("", event, facts);
        String severity = With.text(event, "severity");
        if (severity.isEmpty()) {
            severity = FAILED.matcher(With.text(event, "status") + " " + With.text(event, "conclusion")).find() ? "high" : "low";
        }
        String summary = switch (name) {
            case "post.created" -> "post #" + With.text(event, "postId") + " by " + With.text(event, "agent");
            case "agent.registered" -> "new agent " + With.text(event, "name");
            case "hook.received" -> "webhook from " + firstNonEmpty(With.text(event, "source"), With.text(event, "sender"))
                    + " " + With.oneLine(firstNonEmpty(With.text(event, "title"), With.text(event, "summary")), 200);
            default -> "event " + name;
        };
        // Judging someone else's post needs reasoning; without OpenAI, stay quiet.
        boolean newsworthy = !name.equals("post.created");
        String contentKey=String.join("\n",facts.stream().sorted().toList());
        return new Observation(summary, facts, name + ":" + With.text(event, "id") + ":" + contentKey, severity, newsworthy);
    }

    private static void flatten(String prefix, Map<String, Object> map, List<String> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (out.size() >= 80) return;
            if (prefix.isEmpty() && (e.getKey().equals("event") || e.getKey().equals("id"))) continue;
            String k = prefix + e.getKey();
            if (e.getValue() instanceof Map<?, ?>) {
                flatten(k + ".", With.map(e.getValue()), out);
            } else {
                String v = String.valueOf(e.getValue());
                out.add(k + ": " + (v.length() > 1500 ? v.substring(0, 1499) + "…" : v));
            }
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }
}

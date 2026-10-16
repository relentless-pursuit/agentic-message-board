package dev.agentboard.connect;

import dev.agentboard.rules.Condition;

import java.util.ArrayList;
import java.util.List;

/**
 * What a connector found, in plain words.
 *
 * @param summary one line, shown in the run log
 * @param facts the lines OpenAI reads (and the template fallback shows)
 * @param stableKey only the parts that matter for "did anything change?" (no timings or counters)
 * @param severity the connector's own guess: low, medium, high
 * @param newsworthy false when only a human-like judgement (OpenAI) should decide to post
 */
public record Observation(String summary, List<String> facts, String stableKey, String severity, boolean newsworthy) {

    public static Observation of(String summary, List<String> facts, String stableKey, String severity) {
        return new Observation(summary, facts, stableKey, severity, !facts.isEmpty());
    }

    public static Observation combine(List<Observation> parts) {
        if (parts.size() == 1) return parts.get(0);
        List<String> summaries = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        String severity = "low";
        boolean newsworthy = false;
        for (Observation o : parts) {
            summaries.add(o.summary());
            facts.addAll(o.facts());
            key.append(o.stableKey()).append('\n');
            if (Condition.SEVERITIES.indexOf(o.severity()) > Condition.SEVERITIES.indexOf(severity)) severity = o.severity();
            newsworthy |= o.newsworthy();
        }
        return new Observation(String.join("; ", summaries), facts, key.toString(), severity, newsworthy);
    }
}

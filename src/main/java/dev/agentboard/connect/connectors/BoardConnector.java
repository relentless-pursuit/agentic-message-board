package dev.agentboard.connect.connectors;

import dev.agentboard.connect.Connector;
import dev.agentboard.connect.Observation;
import dev.agentboard.connect.With;
import dev.agentboard.post.PostRepository;
import dev.agentboard.post.PostView;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the other agents posted recently, plus counts per agent. with: { since: 24h }
 * Import completion, clipped fact/summary formatting and the change key were reconstructed.
 */
@Component
public class BoardConnector implements Connector {

    private final PostRepository posts;

    public BoardConnector(PostRepository posts) {
        this.posts = posts;
    }

    @Override
    public String name() {
        return "board";
    }

    @Override
    public Observation fetch(Map<String, Object> with, RunContext context) {
        String since = With.string(with, "since", "24h");
        Instant from = Instant.now().minus(With.duration(since));
        String self = context.agent().handle();
        List<PostView> recent = posts.since(from, 200).stream()
                .filter(p -> !p.agentHandle().equals(self) && p.replyTo() == null)
                .toList();
        List<String> facts = new ArrayList<>();
        for (PostView p : recent.subList(0, Math.min(60, recent.size()))) {
            facts.add("[" + p.agentHandle() + "] " + p.type() + ", " + p.severity() + ": "
                    + With.oneLine(p.title(), 160) + " — " + With.oneLine(p.body(), 300));
        }
        List<PostRepository.AgentStats> stats = posts.statsSince(from);
        long alerts = stats.stream().filter(s -> !s.agent().equals(self)).mapToLong(PostRepository.AgentStats::alerts).sum();
        stats.stream().filter(s -> !s.agent().equals(self))
                .forEach(s -> facts.add("count: " + s.agent() + " posted " + s.posts() + " updates (" + s.alerts() + " alerts)"));
        long agentsActive = recent.stream().map(PostView::agentHandle).distinct().count();
        String summary = recent.size() + " updates (" + alerts + " alerts) from " + agentsActive + " agents";
        String key = recent.isEmpty() ? "empty" : recent.get(0).id() + "-" + recent.size();
        return new Observation(summary, facts, key, "low", !recent.isEmpty());
    }
}

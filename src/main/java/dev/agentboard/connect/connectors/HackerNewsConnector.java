package dev.agentboard.connect.connectors;

import dev.agentboard.connect.Connector;
import dev.agentboard.connect.Observation;
import dev.agentboard.connect.SafeHttp;
import dev.agentboard.connect.With;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The top stories on Hacker News. with: { top: 15 }. Clipped fact formatting was reconstructed. */
@Component
public class HackerNewsConnector implements Connector {

    private static final String API = "https://hacker-news.firebaseio.com/v0/";

    private final SafeHttp http;

    public HackerNewsConnector(SafeHttp http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "hackernews";
    }

    @Override
    public Observation fetch(Map<String, Object> with, RunContext context) throws Exception {
        int top = With.integer(with, "top", 15, 30);
        Object ids = http.getJson(API + "topstories.json", Map.of());
        List<String> facts = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        if (ids instanceof List<?> list) {
            for (Object id : list.subList(0, Math.min(top, list.size()))) {
                Map<String, Object> item = With.map(http.getJson(API + "item/" + id + ".json", Map.of()));
                if (!"story".equals(With.text(item, "type"))) continue;
                facts.add(With.oneLine(With.text(item, "title"), 160) + " — " + With.text(item, "score") + " points, "
                        + With.text(item, "descendants") + " comments — " + (With.text(item, "url").isBlank()?"https://news.ycombinator.com/item?id="+id:With.text(item, "url")));
                if (key.length() < 400) key.append(id).append(',');
            }
        }
        return Observation.of("read the top " + facts.size() + " Hacker News stories", facts, key.toString(), "low");
    }
}

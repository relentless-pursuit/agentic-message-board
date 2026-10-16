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

/** Calls each address and reports whether it answered, and how fast. Clipped latency units were reconstructed. */
@Component
public class HttpCheckConnector implements Connector {

    private final SafeHttp http;

    public HttpCheckConnector(SafeHttp http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "http_check";
    }

    @Override
    public Observation fetch(Map<String, Object> with, RunContext context) throws InterruptedException {
        List<String> urls = With.strings(with, "urls");
        if (urls.isEmpty()) throw new IllegalArgumentException("http_check needs with: { urls: [...] }");
        int slowMs = With.integer(with, "slow_ms", 2000, 60_000);
        List<String> facts = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        int failing = 0;
        int slow = 0;
        for (String url : urls) {
            String state;
            try {
                SafeHttp.Response r = http.get(url, Map.of());
                if (r.status() >= 400) {
                    state = "failing";
                    failing++;
                    facts.add(url + " answered HTTP " + r.status() + " in " + r.millis() + " ms");
                } else if (r.millis() > slowMs) {
                    state = "slow";
                    slow++;
                    facts.add(url + " is slow: HTTP " + r.status() + " in " + r.millis() + " ms");
                } else {
                    state = "ok";
                    facts.add(url + " is fine: HTTP " + r.status() + " in " + r.millis() + " ms");
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                state = "failing";
                failing++;
                facts.add(url + " did not answer: " + e.getMessage());
            }
            key.append(url).append('=').append(state).append('\n');
        }
        int healthy = urls.size() - failing - slow;
        String summary = failing > 0 ? failing + " of " + urls.size() + " addresses failing"
                : slow > 0 ? slow + " of " + urls.size() + " addresses slow"
                : "all " + urls.size() + " addresses healthy";
        String severity = failing > 0 ? "high" : slow > 0 ? "medium" : "low";
        return Observation.of(summary + " (" + healthy + " ok)", facts, key.toString(), severity);
    }
}

package dev.agentboard.connect.connectors;

import dev.agentboard.config.SecretResolver;
import dev.agentboard.connect.Connector;
import dev.agentboard.connect.Observation;
import dev.agentboard.connect.SafeHttp;
import dev.agentboard.connect.With;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Open pull requests of a GitHub repository. Clipped URL parameters, labels and fact formatting were reconstructed. */
@Component
public class GithubPullsConnector implements Connector {

    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    private final SafeHttp http;
    private final SecretResolver secrets;

    public GithubPullsConnector(SafeHttp http, SecretResolver secrets) {
        this.http = http;
        this.secrets = secrets;
    }

    @Override
    public String name() {
        return "github_pulls";
    }

    @Override
    public Observation fetch(Map<String, Object> with, RunContext context) throws Exception {
        String repo = With.string(with, "repo", "");
        if (!REPO.matcher(repo).matches()) throw new IllegalArgumentException("github_pulls needs with: { repo: owner/name }");
        int limit = With.integer(with, "limit", 10, 30);
        Object list = http.getJson("https://api.github.com/repos/" + repo + "/pulls?state=open&per_page=" + limit,
                GithubReleasesConnector.GithubApi.headers(secrets));
        List<String> facts = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        if (list instanceof List<?> pulls) {
            for (Object o : pulls) {
                Map<String, Object> pr = With.map(o);
                String number = With.text(pr, "number");
                String updated = With.text(pr, "updated_at");
                List<String> labels = new ArrayList<>();
                if (pr.get("labels") instanceof List<?> ls) ls.forEach(l -> labels.add(With.text(l, "name")));
                facts.add("#" + number + " " + With.oneLine(With.text(pr, "title"), 160)
                        + " (by " + With.text(pr.get("user"), "login")
                        + (Boolean.TRUE.equals(pr.get("draft")) ? ", draft" : "")
                        + (labels.isEmpty() ? "" : ", labels: " + String.join(", ", labels))
                        + ", updated " + (updated.length() >= 10 ? updated.substring(0, 10) : updated) + ") "
                        + With.oneLine(With.text(pr, "body"), 300));
                key.append(number).append('@').append(updated).append('\n');
            }
        }
        String summary = facts.size() + " open pull request" + (facts.size() == 1 ? "" : "s") + " in " + repo;
        return Observation.of(summary, facts, key.toString(), "low");
    }
}

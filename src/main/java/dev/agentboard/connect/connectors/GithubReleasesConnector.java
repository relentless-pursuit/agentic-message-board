package dev.agentboard.connect.connectors;

import dev.agentboard.config.SecretResolver;
import dev.agentboard.connect.Connector;
import dev.agentboard.connect.Observation;
import dev.agentboard.connect.SafeHttp;
import dev.agentboard.connect.With;
import dev.agentboard.run.RunContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * New releases of public GitHub repositories. with: { repos: [owner/name, ...], days: 14 }
 * Clipped patterns, filtering, URL suffix and summary/fact formatting were reconstructed.
 */
@Component
public class GithubReleasesConnector implements Connector {

    private static final Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern SECURITY = Pattern.compile("(?i)security|cve-\\d|vulnerab");
    private static final Pattern BREAKING = Pattern.compile("(?i)breaking|deprecat|removed");

    private final SafeHttp http;
    private final SecretResolver secrets;

    public GithubReleasesConnector(SafeHttp http, SecretResolver secrets) {
        this.http = http;
        this.secrets = secrets;
    }

    @Override
    public String name() {
        return "github_releases";
    }

    @Override
    public Observation fetch(Map<String, Object> with, RunContext context) throws Exception {
        List<String> repos = With.strings(with, "repos");
        if (repos.isEmpty()) throw new IllegalArgumentException("github_releases needs with: { repos: [owner/name] }");
        int days = With.integer(with, "days", 14, 365);
        Instant since = Instant.now().minus(Duration.ofDays(days));
        List<String> facts = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        String severity = "low";
        int found = 0;
        for (String repo : repos) {
            if (!REPO.matcher(repo).matches()) throw new IllegalArgumentException("invalid repository: " + repo);
            Object list = http.getJson("https://api.github.com/repos/" + repo + "/releases?per_page=30", GithubApi.headers(secrets));
            if (!(list instanceof List<?> releases)) continue;
            for (Object o : releases) {
                Map<String, Object> rel = With.map(o);
                String published = With.text(rel, "published_at");
                if (Boolean.TRUE.equals(rel.get("draft")) || published.isEmpty() || Instant.parse(published).isBefore(since)) continue;
                String notes = With.text(rel, "body");
                String tag = With.text(rel, "tag_name");
                found++;
                facts.add(repo + " " + tag + " (" + published.substring(0, 10) + (Boolean.TRUE.equals(rel.get("prerelease")) ? ", prerelease" : "")
                        + "): " + With.oneLine(notes, 400));
                key.append(repo).append('@').append(tag).append('\n');
                if (SECURITY.matcher(notes).find()) severity = "high";
                else if (BREAKING.matcher(notes).find() && !severity.equals("high")) severity = "medium";
            }
        }
        String summary = found == 0 ? "no new releases in the last " + days + " days"
                : found + " new release" + (found == 1 ? "" : "s") + " in " + repos.size() + " repositories";
        return Observation.of(summary, facts, key.toString(), severity);
    }

    static final class GithubApi {
        private GithubApi() {
        }

        static Map<String, String> headers(SecretResolver secrets) {
            Map<String, String> h = new HashMap<>();
            h.put("Accept", "application/vnd.github+json");
            secrets.resolve("GITHUB_TOKEN").ifPresent(t -> h.put("Authorization", "Bearer " + t));
            return h;
        }
    }
}

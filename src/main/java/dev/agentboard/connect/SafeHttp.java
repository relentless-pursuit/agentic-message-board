package dev.agentboard.connect;

import dev.agentboard.config.BoardProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The only way connectors reach the network. Every call is checked against the host allow-list,
 * limited in time and size, never follows redirects to other hosts, and goes through a circuit breaker.
 * ObjectMapper uses Jackson 2 to match this project's Spring Boot 3.1 baseline.
 */
@Component
public class SafeHttp {

    private static final String USER_AGENT = "agent-board/0.1 (+https://github.com)";

    private final HttpClient client;
    private final BoardProperties.Http config;
    private final Set<String> allowedHosts;
    private final CircuitBreaker breaker;
    private final ObjectMapper json;

    public SafeHttp(BoardProperties props, ObjectMapper json) {
        this.config = props.getHttp();
        this.allowedHosts = config.getAllowedHosts().stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.breaker = new CircuitBreaker(config.getBreakerFailures(), config.getBreakerOpenFor());
        this.json = json;
        this.client = HttpClient.newBuilder()
                .connectTimeout(config.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        URI uri = checked(url);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        breaker.check(host);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(config.getTimeout()).GET().header("User-Agent", USER_AGENT);
        headers.forEach(request::header);
        long start = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(config.getMaxBodyBytes() + 1);
                if (bytes.length > config.getMaxBodyBytes()) {
                    throw new IOException("response from " + host + " is larger than " + config.getMaxBodyBytes() + " bytes");
                }
                body = new String(bytes, StandardCharsets.UTF_8);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (response.statusCode() >= 500) breaker.failure(host); else breaker.success(host);
            return new Response(response.statusCode(), body, ms);
        } catch (IOException e) {
            breaker.failure(host);
            throw new IOException("could not reach " + host + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }

    /** Get a JSON document and return it as plain Java maps and lists. Non-2xx is an error. */
    public Object getJson(String url, Map<String, String> headers) throws IOException, InterruptedException {
        Response r = get(url, headers);
        if (r.status() / 100 != 2) {
            throw new IOException(host(url) + " answered HTTP " + r.status());
        }
        return json.readValue(r.body(), Object.class);
    }

    public void validateAddress(String url){checked(url);}

    URI checked(String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a valid address: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("only https addresses are allowed: " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("addresses with a user name are not allowed");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedHosts.contains(host)) {
            throw new IllegalArgumentException(host + " is not on the allow-list (board.http.allowed-hosts)");
        }
        return uri;
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return url;
        }
    }

    public record Response(int status, String body, long millis) {
    }
}

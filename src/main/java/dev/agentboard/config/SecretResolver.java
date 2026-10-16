package dev.agentboard.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns a secret's NAME into its value. Agent files only ever contain names; values come from
 * the environment today and could come from a vault tomorrow without changing any caller.
 */
@Component
public class SecretResolver {

    public static final Pattern NAME = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    private final Environment environment;

    public SecretResolver(Environment environment) {
        this.environment = environment;
    }

    public Optional<String> resolve(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        String value = environment.getProperty(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}

package dev.agentboard.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationTest {
    @Test void bindsNestedSettingsAndDurationValues() {
        var source = new MapConfigurationPropertySource(Map.of(
                "board.openai.timeout", "45s", "board.http.allowed-hosts[0]", "example.org",
                "board.webhooks.targets.teams", "https://example.org/hook",
                "board.webhooks.subscriptions[0].target", "teams",
                "board.webhooks.subscriptions[0].when", "severity >= high"));
        BoardProperties props = new Binder(source).bind("board", Bindable.of(BoardProperties.class)).get();
        assertThat(props.getOpenai().getTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(props.getHttp().getAllowedHosts()).containsExactly("example.org");
        assertThat(props.getWebhooks().getTargets()).containsEntry("teams", "https://example.org/hook");
        assertThat(props.getWebhooks().getSubscriptions().get(0).getWhen()).isEqualTo("severity >= high");
    }

    @Test void separateProcessesRequireSharedQueue() {
        BoardProperties props = new BoardProperties();
        new StartupChecks(props).afterPropertiesSet();
        for (String role : new String[]{"web", "worker"}) {
            props.setRole(role);
            assertThatThrownBy(() -> new StartupChecks(props).afterPropertiesSet())
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("BOARD_QUEUE=database");
        }
        props.setQueue("database");
        new StartupChecks(props).afterPropertiesSet();
    }

    @Test void invalidRoleQueueAndTimezoneFailAtStartup() {
        BoardProperties props = new BoardProperties();
        props.setRole("invalid");
        assertThatThrownBy(() -> new StartupChecks(props).afterPropertiesSet()).hasMessageContaining("board.role");
        props.setRole("all");
        props.setQueue("invalid");
        assertThatThrownBy(() -> new StartupChecks(props).afterPropertiesSet()).hasMessageContaining("board.queue");
        props.setQueue("memory");
    }

    @Test void resolvesOnlyValidPresentNonblankSecretNames() {
        var environment = new MockEnvironment().withProperty("DEMO_TOKEN", " demo-value ")
                .withProperty("BLANK_TOKEN", " ").withProperty("invalid-name", "not-readable");
        var resolver = new SecretResolver(environment);
        assertThat(resolver.resolve("DEMO_TOKEN")).contains("demo-value");
        for (String name : new String[]{"BLANK_TOKEN", "MISSING_TOKEN", "invalid-name", "AB"}) {
            assertThat(resolver.resolve(name)).isEmpty();
        }
        assertThat(resolver.resolve(null)).isEmpty();
    }
}

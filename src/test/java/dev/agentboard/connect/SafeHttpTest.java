package dev.agentboard.connect;

import dev.agentboard.config.BoardProperties;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeHttpTest {
    @Test void validatesExactAllowedHttpsHostsBeforeAnyNetworkRequest() throws Exception {
        var props = new BoardProperties();
        props.getHttp().setAllowedHosts(List.of(" API.EXAMPLE.ORG "));
        var http = new SafeHttp(props, new ObjectMapper());
        assertThat(http.checked("https://api.example.org/path?q=value").getHost()).isEqualTo("api.example.org");
        assertThatThrownBy(() -> http.get("http://api.example.org/", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only https");
        assertThatThrownBy(() -> http.get("https://api.example.org.attacker.invalid/", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not on the allow-list");
        assertThatThrownBy(() -> http.get("https://user@api.example.org/", Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("user name");
    }

    @Test void emptyAllowListDeniesAllDestinations() {
        var http = new SafeHttp(new BoardProperties(), new ObjectMapper());
        assertThatThrownBy(() -> http.checked("https://example.org/"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not on the allow-list");
        assertThatThrownBy(() -> http.checked("not a URI"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a valid address");
    }
}

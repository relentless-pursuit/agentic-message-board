package dev.agentboard.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** With board.auth=open only a browser on the same computer, on this board's own page, acts as the operator. */
class OpenAccessTest {

    private static MockHttpServletRequest request(String host, int port, String origin) {
        var r = new MockHttpServletRequest("POST", "/api/workflows/agents");
        r.setServerName(host);
        r.setServerPort(port);
        if (origin != null) {
            r.addHeader("Origin", origin);
        }
        return r;
    }

    @Test
    void sameComputerRequestsAreLocal() {
        assertThat(BearerTokenFilter.isLocalRequest(request("127.0.0.1", 8087, null))).isTrue();
        assertThat(BearerTokenFilter.isLocalRequest(request("localhost", 8087, "http://localhost:8087"))).isTrue();
        assertThat(BearerTokenFilter.isLocalRequest(request("[::1]", 8087, "http://[::1]:8087"))).isTrue();
    }

    @Test
    void otherSitesAndOtherHostsAreNot() {
        // A page on another site posting to the board (cross-site request).
        assertThat(BearerTokenFilter.isLocalRequest(request("127.0.0.1", 8087, "https://evil.example"))).isFalse();
        // Another local app on a different port.
        assertThat(BearerTokenFilter.isLocalRequest(request("127.0.0.1", 8087, "http://127.0.0.1:3000"))).isFalse();
        // DNS rebinding: an attacker's name that resolves to 127.0.0.1.
        assertThat(BearerTokenFilter.isLocalRequest(request("evil.example", 8087, null))).isFalse();
        // The board reached over the network.
        assertThat(BearerTokenFilter.isLocalRequest(request("10.0.0.5", 8087, null))).isFalse();
        assertThat(BearerTokenFilter.isLocalRequest(request("127.0.0.1", 8087, "null"))).isFalse();
    }
}

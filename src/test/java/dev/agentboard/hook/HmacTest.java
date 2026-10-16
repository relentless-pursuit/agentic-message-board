package dev.agentboard.hook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacTest {
    @Test void signatureRejectsChangedBodyTimestampAndSecret() {
        String body = "{\"status\":\"ok\"}";
        String signed = Hmac.sign("test-secret", "1700000000", body);
        // Independently calculated with Python's standard hmac/hashlib implementation.
        assertThat(signed).isEqualTo("sha256=dccd4e44cef86417bdad978cd67c25fc9607cc0fbf109b52fc5072e6cc04f70b");
        assertThat(Hmac.verify("test-secret", "1700000000", body, signed)).isTrue();
        assertThat(Hmac.verify("test-secret", "1700000000", "{}", signed)).isFalse();
        assertThat(Hmac.verify("test-secret", "1700000001", body, signed)).isFalse();
        assertThat(Hmac.verify("other-secret", "1700000000", body, signed)).isFalse();
        assertThat(Hmac.verify("test-secret", null, body, signed)).isFalse();
        assertThat(Hmac.verify("test-secret", "1700000000", body, null)).isFalse();
    }

    @Test void sha256MatchesKnownVector() {
        assertThat(Hmac.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}

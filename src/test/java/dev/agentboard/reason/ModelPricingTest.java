package dev.agentboard.reason;

import dev.agentboard.config.BoardProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Models come from the user's OpenAI account; the budget guard only needs a price for each. */
class ModelPricingTest {

    private final BoardProperties.Openai config = new BoardProperties().getOpenai();

    @Test
    void listedModelsAndTheirDatedSnapshotsUseTheListedPrice() {
        assertThat(config.price("gpt-5.4-mini").orElseThrow().getInput()).isEqualTo(0.75);
        assertThat(config.price("gpt-5.4-mini-2026-03-17").orElseThrow().getOutput()).isEqualTo(4.50);
        // The longest listed id wins: gpt-5.4-nano must not be priced as gpt-5.4.
        assertThat(config.price("gpt-5.4-nano-2026-03-17").orElseThrow().getId()).isEqualTo("gpt-5.4-nano");
    }

    @Test
    void anUnlistedModelIsPricedAtTheDeliberatelyHighFallback() {
        assertThat(config.price("gpt-9").isEmpty()).isTrue();
        assertThat(config.priceOrFallback("gpt-9").getOutput()).isEqualTo(config.getFallbackPrice().getOutput());
        assertThat(config.getFallbackPrice().getOutput()).isGreaterThan(config.price("gpt-5.4").orElseThrow().getOutput());
    }

    @Test
    void costUsesCachedAndOutputRatesSeparately() {
        var nano = config.price("gpt-5.4-nano").orElseThrow();
        assertThat(nano.cost(1000, 400, 200)).isEqualTo(0.000378, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void onlyChatModelsAreOffered() {
        assertThat(OpenAiReasoner.usableForAgents("gpt-5.4-mini")).isTrue();
        assertThat(OpenAiReasoner.usableForAgents("o3")).isTrue();
        assertThat(OpenAiReasoner.usableForAgents("text-embedding-3-small")).isFalse();
        assertThat(OpenAiReasoner.usableForAgents("gpt-4o-realtime-preview")).isFalse();
        assertThat(OpenAiReasoner.usableForAgents("gpt-image-1")).isFalse();
        assertThat(OpenAiReasoner.usableForAgents("whisper-1")).isFalse();
    }
}

package dev.agentboard.rules;

import dev.agentboard.connect.Observation;

import dev.agentboard.registry.AgentDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleWorkflowTest {
    @Test void combinesSeverityOrderingAndCaseInsensitiveTagMembership() {
        var rule = Condition.parse("severity >= high and tag = security");
        assertThat(rule.test(Map.of("severity", "critical", "tags", List.of("SECURITY")))).isTrue();
        assertThat(rule.test(Map.of("severity", "medium", "tags", List.of("security")))).isFalse();
        assertThat(rule.test(Map.of("severity", "high", "tags", List.of("ops")))).isFalse();
        assertThat(Condition.parse("tag != security").test(Map.of("tags", List.of("ops")))).isTrue();
    }

    @Test void rejectsUnknownFieldsInvalidSeverityAndOrderingOnOtherFields() {
        assertThatThrownBy(() -> Condition.parse("owner = team"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown field");
        assertThatThrownBy(() -> Condition.parse("severity = urgent"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("severity must be");
        assertThatThrownBy(() -> Condition.parse("type > alert"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only works with severity");
        assertThatThrownBy(() -> Condition.parse("not a rule"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot read rule");
        assertThat(Condition.parse(null).test(Map.of())).isTrue();
    }

    @Test void combinesObservationsAndKeepsFallbackQuietForJudgementOnlyEvents() {
        var first = new Observation("first", List.of("measured first fact"), "one", "low", false);
        var second = new Observation("second", List.of("measured second fact"), "two", "critical", true);
        var combined = Observation.combine(List.of(first, second));
        assertThat(combined.severity()).isEqualTo("critical");
        assertThat(combined.stableKey()).isEqualTo("one\ntwo\n");
        assertThat(combined.facts()).containsExactly("measured first fact", "measured second fact");
        var agent = new AgentDefinition("test-agent", "Test Agent", "Summarise measurements", "test-team",
                null, "company", List.of("ops"), null, List.of(), null, List.of(), true);
    }
}

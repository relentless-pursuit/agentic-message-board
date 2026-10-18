package dev.agentboard.web;

import dev.agentboard.audit.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The Docs check page starts DocDrift with an argument list; anything that is not a plain repository or version is refused first. */
class DocDriftControllerTest {

    private final DocDriftController controller = new DocDriftController(mock(AuditLog.class), new ObjectMapper());

    @Test
    void listsAvailabilityAndNoRunsAtFirst() {
        var out = controller.list();
        assertThat(out).containsKeys("available", "runs");
        assertThat((java.util.List<?>) out.get("runs")).isEmpty();
    }

    @Test
    void refusesInputThatCouldBecomeACommandOrGitOption() {
        var request = new MockHttpServletRequest();
        for (var bad : java.util.List.of(
                new DocDriftController.Start("evil; rm -rf /", "1.0", "main", "auto"),
                new DocDriftController.Start("pallets/click", "--upload-pack=x", "main", "auto"),
                new DocDriftController.Start("pallets/click", "1.0", "a..b", "auto"),
                new DocDriftController.Start("https://example.com/a/b", "1.0", "main", "auto"))) {
            assertThatThrownBy(() -> controller.start(bad, request)).isInstanceOf(ResponseStatusException.class);
        }
    }
}

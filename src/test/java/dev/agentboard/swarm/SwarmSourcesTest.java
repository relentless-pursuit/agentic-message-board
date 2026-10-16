package dev.agentboard.swarm;

import dev.agentboard.config.BoardProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SwarmSourcesTest {
    final BoardProperties props = new BoardProperties();
    final SwarmSources sources = new SwarmSources(props, new ObjectMapper());

    @Test void pdfUploadProducesEditableTextFromTheSyntheticDocument() throws Exception {
        assumeTrue(Files.isExecutable(Path.of(props.getCodex().getRuntimeRoot(), "python/bin/python3")), "Bundled document runtime is unavailable");
        try (var stream = getClass().getResourceAsStream("/swarm/synthetic-cv.pdf")) {
            var upload = new MockMultipartFile("file", "synthetic-cv.pdf", "application/pdf", stream.readAllBytes());
            var result = sources.importSource(null, upload);
            assertThat(result.get("text").toString()).contains("Alex Example", "Java and Spring Boot", "fictional test data only").doesNotContain("%PDF");
            assertThat(result.get("truncated")).isEqualTo(false);
        }
    }

    @Test void ambiguousAndOversizedUploadsAreRejectedBeforeExtraction() {
        var text = new MockMultipartFile("file", "sample.txt", "text/plain", "Synthetic CV".getBytes());
        assertThatThrownBy(() -> sources.importSource("https://docs.google.com/document/example", text)).hasMessageContaining("one public URL or one file");
        var oversized = new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[2_000_001]);
        assertThatThrownBy(() -> sources.importSource(null, oversized)).hasMessageContaining("up to 2 MB");
    }
}

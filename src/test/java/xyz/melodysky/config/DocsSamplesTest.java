package xyz.melodysky.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DocsSamplesTest {
    @Test
    void betaSamplesDocumentConfigCommandsOutputAndReportHighlights() throws Exception {
        assertSample("basic-cli-app.md", "docs/examples/minimal-config.json", "hello beta count=1 opt=beta");
        assertSample("reflection-service-app.md", "docs/examples/protection-all-on-config.json", "beta:7");
    }

    private void assertSample(String fileName, String exampleConfig, String expectedOutput) throws Exception {
        Path sample = Path.of("docs/samples").resolve(fileName);
        String markdown = Files.readString(sample);
        assertTrue(markdown.contains("\"schemaVersion\": 1"), markdown);
        assertTrue(markdown.contains("java -jar build/dist/j2ll/j2ll.jar build"), markdown);
        assertTrue(markdown.contains(expectedOutput), markdown);
        assertTrue(markdown.contains("reports/index.json"), markdown);
        assertTrue(markdown.contains("report"), markdown);
        assertTrue(Files.isRegularFile(Path.of(exampleConfig)), exampleConfig);
    }
}

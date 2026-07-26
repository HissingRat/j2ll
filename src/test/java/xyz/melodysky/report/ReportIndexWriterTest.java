package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportIndexWriterTest {
    @TempDir
    Path temp;

    @Test
    void indexesReportsWithVersionHashReadinessAndStatus() throws Exception {
        Path reports = temp.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("diagnostics.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "diagnostics": [
                    {"severity": "warning", "code": "W", "stage": "CONFIG", "message": "warn"}
                  ]
                }
                """);
        Files.writeString(reports.resolve("artifact-audit.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "passed": false,
                  "checks": []
                }
                """);
        Files.writeString(reports.resolve("summary.md"), "# summary\n");

        Path index = new ReportIndexWriter().write(temp);
        String json = Files.readString(index);
        var root = JsonParser.parseString(json).getAsJsonObject();
        var entries = root.getAsJsonArray("reports");

        assertTrue(json.indexOf("\"schemaVersion\"") < json.indexOf("\"reportVersion\""), json);
        assertEquals(3, entries.size());
        assertEquals("reports/artifact-audit.json", entries.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("failed", entries.get(0).getAsJsonObject().get("status").getAsString());
        assertTrue(entries.get(0).getAsJsonObject().get("requiredForReadiness").getAsBoolean());
        assertTrue(entries.get(0).getAsJsonObject().get("requiredForBeta").getAsBoolean());
        assertTrue(entries.get(0).getAsJsonObject().get("requiredForRc").getAsBoolean());
        assertTrue(entries.get(0).getAsJsonObject().has("producedOnFailure"));
        assertEquals(sha256(reports.resolve("artifact-audit.json")),
                entries.get(0).getAsJsonObject().get("sha256").getAsString());
        assertEquals("warning", entries.get(1).getAsJsonObject().get("status").getAsString());
        assertTrue(entries.get(2).getAsJsonObject().get("requiredForReadiness").getAsBoolean());
        assertTrue(entries.get(2).getAsJsonObject().get("requiredForBeta").getAsBoolean());
        assertTrue(entries.get(2).getAsJsonObject().get("reportVersion").isJsonNull());
    }

    @Test
    void fieldInternalizationReportIsRequiredForReadinessBetaRcAndFailureEvidence() throws Exception {
        Path reports = temp.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("field-internalization-report.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "enabled": false,
                  "decisions": []
                }
                """);

        var root = JsonParser.parseString(new ReportIndexWriter().json(temp)).getAsJsonObject();
        var entry = root.getAsJsonArray("reports").get(0).getAsJsonObject();

        assertEquals(
                "reports/field-internalization-report.json",
                entry.get("path").getAsString());
        assertTrue(entry.get("requiredForReadiness").getAsBoolean());
        assertTrue(entry.get("requiredForBeta").getAsBoolean());
        assertTrue(entry.get("requiredForRc").getAsBoolean());
        assertTrue(entry.get("producedOnFailure").getAsBoolean());
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}

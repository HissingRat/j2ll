package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SummaryReportWriterTest {
    @TempDir
    Path temp;

    @Test
    void summarizesUserFacingStatusWithoutSensitivePlaintext() throws Exception {
        Path reports = temp.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("diagnostics.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "diagnostics": [
                    {"severity": "warning", "code": "UNSUPPORTED_EXCEPTION_STATE_MERGE", "stage": "LOWERING", "message": "method skipped"}
                  ]
                }
                """);
        Files.writeString(reports.resolve("lowering-report.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "requestedMethods": [
                    {"status": "nativeLowered"},
                    {"status": "skipped"}
                  ],
                  "ineligible": [{"status": "ineligible"}],
                  "excluded": [{"status": "excluded"}]
                }
                """);
        Files.writeString(reports.resolve("packaging-report.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "outputJar": "app.jar",
                  "zigToolchain": {
                    "targetArtifacts": [
                      {
                        "target": "macos-aarch64",
                        "required": true,
                        "currentHost": true,
                        "status": "built",
                        "expectedResourcePath": "native/libj2ll.dylib",
                        "actualSha256": "abc123",
                        "failureKind": null
                      }
                    ]
                  }
                }
                """);
        Files.writeString(reports.resolve("protection-report.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "seedHash": "hash-only",
                  "passes": [
                    {"passName": "STRING_ENCRYPTION", "status": "RAN"},
                    {"passName": "CFF", "status": "SKIPPED"}
                  ]
                }
                """);
        Files.writeString(reports.resolve("artifact-audit.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "passed": true,
                  "checkedSensitiveFacts": [{"literalHash": "sha256:abc"}],
                  "observedOnlySensitiveFacts": [{"literalHash": "sha256:def"}],
                  "checks": []
                }
                """);
        Files.writeString(reports.resolve("release-readiness.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "status": "passed",
                  "strictModePassed": true,
                  "missingEvidence": []
                }
                """);
        Files.writeString(reports.resolve("known-blockers.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "blockers": [
                    {"id": "complex-finally", "reasonCode": "UNSUPPORTED_EXCEPTION_STATE_MERGE", "reportLocation": "reports/skipped-method-report.json"}
                  ]
                }
                """);

        Path summaryPath = new SummaryReportWriter().write(temp, "build", true);
        String json = Files.readString(summaryPath);
        var summary = JsonParser.parseString(json).getAsJsonObject();

        assertTrue(json.indexOf("\"schemaVersion\"") < json.indexOf("\"reportVersion\""), json);
        assertEquals("passed", summary.get("status").getAsString());
        assertEquals("app.jar", summary.get("outputJar").getAsString());
        assertEquals(1, summary.getAsJsonObject("diagnostics").get("warnings").getAsInt());
        assertEquals(1, summary.getAsJsonObject("methods").get("nativeLowered").getAsInt());
        assertEquals(1, summary.getAsJsonObject("methods").get("skipped").getAsInt());
        assertEquals(1, summary.getAsJsonObject("methods").get("ineligible").getAsInt());
        assertEquals(1, summary.getAsJsonObject("methods").get("excluded").getAsInt());
        assertEquals(1, summary.getAsJsonObject("protection").get("blockingSensitiveFacts").getAsInt());
        assertEquals("built", summary.getAsJsonArray("nativeTargets")
                .get(0).getAsJsonObject().get("status").getAsString());
        assertTrue(!json.contains("not-copied-to-summary"), json);
    }
}

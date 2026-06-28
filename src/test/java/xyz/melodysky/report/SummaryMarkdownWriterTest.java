package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SummaryMarkdownWriterTest {
    @TempDir
    Path temp;

    @Test
    void writesStableHumanSummaryWithoutSeed() throws Exception {
        Path reports = temp.resolve("reports");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("summary.json"), """
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "status": "passed",
                  "finalArtifactWritten": true,
                  "outputJar": "output/app.jar",
                  "reportsDir": "workspace/reports",
                  "diagnostics": {"errors": 0, "warnings": 1},
                  "methods": {"lowered": 2, "halfLowered": 1, "frontendSkipped": 0, "notApplicable": 1, "failed": 0},
                  "nativeTargets": [
                    {
                      "target": "linux-x64",
                      "required": true,
                      "currentHost": false,
                      "status": "failed",
                      "resourcePath": "native/linux-x64/x64-linux.so",
                      "failureKind": "unsupportedLibc"
                    },
                    {
                      "target": "macos-arm64",
                      "required": true,
                      "currentHost": true,
                      "status": "built",
                      "resourcePath": "native/macos-arm64/arm64-macos.dylib",
                      "failureKind": "none"
                    }
                  ],
                  "artifactAudit": {"status": "passed"},
                  "readiness": {"status": "passed", "missingEvidenceCount": 0},
                  "protectionSeedHash": "hash-only"
                }
                """);
        Files.writeString(reports.resolve("known-blockers.json"), """
                {
                  "blockers": [
                    {"id":"beta-docs","reasonCode":"DOCS_SAMPLE_MISSING","severity":"beta-blocker","targetMilestone":"beta"},
                    {"id":"cross-build","reasonCode":"ZIG_TARGET_UNBUILDABLE","severity":"rc-blocker","targetMilestone":"rc"},
                    {"id":"native-image","reasonCode":"EXPLICIT_NONGOAL_STANDALONE_NATIVE_IMAGE","severity":"non-goal","targetMilestone":"explicit-nongoal"}
                  ]
                }
                """);

        String markdown = Files.readString(new SummaryMarkdownWriter().write(temp));

        assertTrue(markdown.contains("# j2ll build summary"), markdown);
        assertTrue(markdown.contains("- Status: passed"), markdown);
        assertTrue(markdown.contains("- Output JAR: output/app.jar"), markdown);
        assertTrue(markdown.contains("- Warnings: 1"), markdown);
        assertTrue(markdown.contains("- Half lowered: 1"), markdown);
        assertTrue(markdown.contains("## Native Targets"), markdown);
        assertTrue(markdown.contains("- Built/buildable: macos-arm64 built native/macos-arm64/arm64-macos.dylib"), markdown);
        assertTrue(markdown.contains("- Unbuildable: linux-x64 failed native/linux-x64/x64-linux.so failureKind=unsupportedLibc"), markdown);
        assertTrue(markdown.contains("- Beta blockers: beta-docs (DOCS_SAMPLE_MISSING)"), markdown);
        assertTrue(markdown.contains("- 1.0 blockers: cross-build (ZIG_TARGET_UNBUILDABLE)"), markdown);
        assertTrue(markdown.contains("- Future/non-goals: native-image (EXPLICIT_NONGOAL_STANDALONE_NATIVE_IMAGE)"), markdown);
        assertTrue(!markdown.contains("hash-only"), markdown);
    }
}

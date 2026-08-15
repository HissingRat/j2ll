package xyz.melodysky.testsupport.dummy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public final class DummyReportAsserter {
    private static final List<String> REQUIRED_REPORTS = List.of(
            "artifact-audit.json",
            "index.json",
            "known-blockers.json",
            "lowering-report.json",
            "packaging-report.json",
            "protection-report.json",
            "release-readiness.json",
            "skipped-method-report.json",
            "summary.json",
            "summary.md",
            "support-matrix.json",
            "symbol-audit.json");

    private DummyReportAsserter() {}

    public static void assertProfile(
            String profile,
            Path workspace,
            Path outputJar,
            List<String> failures) {
        Path reports = workspace.resolve("reports");
        for (String report : REQUIRED_REPORTS) {
            if (!Files.isRegularFile(reports.resolve(report))) {
                failures.add("reports: missing " + report);
            }
        }
        try {
            String allReports = Files.isDirectory(reports) ? allReportText(reports) : "";
            Path artifactAuditPath = reports.resolve("artifact-audit.json");
            if (Files.isRegularFile(artifactAuditPath)
                    && !Files.readString(artifactAuditPath).contains("\"passed\": true")) {
                failures.add("audit: artifact-audit.json did not pass");
            }
            Path readinessPath = reports.resolve("release-readiness.json");
            if (Files.isRegularFile(readinessPath)) {
                String readiness = Files.readString(readinessPath);
                if (!readiness.contains("\"status\": \"passed\"")) {
                    failures.add("readiness: release-readiness.json did not pass");
                }
                if (!readiness.contains("\"finalArtifactWritten\": true")) {
                    failures.add("readiness: finalArtifactWritten was not true");
                }
            }
            /*
             * DummyMethodOutcomeAsserter owns the exact selected-method
             * outcome contract, including the complete skipped set and each
             * stable reason code.  A blanket "basic has no skips" assertion
             * would reject deliberately documented unsupported boundaries
             * such as multianewarray while adding no independent evidence.
             */
            if (allReports.contains("dummy-secret-seed")) {
                failures.add("privacy: raw protection seed leaked into reports");
            }
            if (allReports.contains("/obfuscator/src/") || allReports.contains("\\obfuscator\\src\\")) {
                failures.add("audit: legacy obfuscator path appeared in reports");
            }
            Path symbolAuditPath = reports.resolve("symbol-audit.json");
            if (Files.isRegularFile(symbolAuditPath)) {
                String symbolAudit = Files.readString(symbolAuditPath);
                if (symbolAudit.contains("j2ll_f_") || symbolAudit.contains("j2ll_cit_")) {
                    failures.add("symbols: hidden/internal protection symbols appeared in symbol audit exports");
                }
            }
        } catch (Exception exception) {
            failures.add("reports: failed to inspect reports: " + exception.getMessage());
        }
        assertJarMetadata(outputJar, failures);
    }

    private static String allReportText(Path reports) throws Exception {
        ArrayList<String> parts = new ArrayList<>();
        try (var stream = Files.list(reports)) {
            for (Path report : stream.filter(Files::isRegularFile).sorted().toList()) {
                parts.add(Files.readString(report));
            }
        }
        return String.join("\n", parts);
    }

    private static void assertJarMetadata(Path outputJar, List<String> failures) {
        if (!Files.isRegularFile(outputJar)) {
            failures.add("jar: output jar missing: " + outputJar);
            return;
        }
        try (JarFile jar = new JarFile(outputJar.toFile(), false)) {
            if (jar.stream().anyMatch(entry -> entry.getName()
                    .toLowerCase(java.util.Locale.ROOT)
                    .startsWith("meta-inf/j2ll/"))) {
                failures.add("jar: private META-INF/j2ll metadata subtree is present");
            }
            if (jar.stream().anyMatch(entry -> entry.getName().contains("/J2llFallback$")
                    && entry.getName().endsWith(".class"))) {
                failures.add("jar: plaintext generated fallback helper class is present");
            }
            assertTrue(jar.getManifest() != null, "output jar should retain a manifest");
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("obfuscator/")),
                    "output jar must not contain legacy output path");
        } catch (AssertionError assertion) {
            failures.add("jar: " + assertion.getMessage());
        } catch (Exception exception) {
            failures.add("jar: failed to inspect output jar: " + exception.getMessage());
        }
    }
}

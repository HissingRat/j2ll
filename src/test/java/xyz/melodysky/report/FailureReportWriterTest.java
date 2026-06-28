package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticCode;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;

class FailureReportWriterTest {
    @Test
    void writesStableFailureSummaryWithFinalArtifactState() {
        Diagnostic diagnostic = Diagnostic.error(
                        DiagnosticStage.CONFIG,
                        DiagnosticCode.of("INVALID_SELECTOR"),
                        "whiteList contains invalid selector")
                .at(DiagnosticLocation.methodLocation("pkg/Foo", "run", "()V"));

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "finalArtifactWritten": false,
                  "primaryDiagnosticId": "CONFIG:INVALID_SELECTOR",
                  "failures": [
                    {
                      "stage": "CONFIG",
                      "reasonCode": "INVALID_SELECTOR",
                      "message": "whiteList contains invalid selector",
                      "hint": "Use owner/Class#method!(descriptor), for example example/Adder#add!(II)I.",
                      "decision": "failed",
                      "affectedClass": "pkg/Foo",
                      "affectedMethod": "run",
                      "affectedDescriptor": "()V",
                      "affectedArtifact": "pkg/Foo#run!()V"
                    }
                  ]
                }
                """, new FailureReportWriter().json(List.of(diagnostic), false));
    }
}

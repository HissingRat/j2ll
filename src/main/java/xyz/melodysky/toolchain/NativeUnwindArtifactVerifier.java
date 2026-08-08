package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import xyz.melodysky.toolchain.symbols.NativeUnwindSectionInspection;

/** Blocks a final artifact that contradicts a proven unwind-omission plan. */
public final class NativeUnwindArtifactVerifier {
    public void verify(
            NativeLlvmUnwindTargetSummary summary,
            NativeUnwindSectionInspection inspection,
            Path libraryPath) throws IOException {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(inspection, "inspection");
        Objects.requireNonNull(libraryPath, "libraryPath");
        if (summary.generatedCDecision().target() != inspection.target()) {
            throw new IOException(
                    "NATIVE_UNWIND_AUDIT_TARGET_MISMATCH: planned "
                            + summary.generatedCDecision().target().directoryName()
                            + " but inspected "
                            + inspection.target().directoryName());
        }
        if (summary.finalOmissionExpected()
                && inspection.hasNonEmptyUnwindSection()) {
            throw new IOException(
                    "NATIVE_UNWIND_OMISSION_AUDIT_FAILED: "
                            + inspection.target().directoryName()
                            + " artifact still contains unwind sections "
                            + inspection.sectionSizes()
                            + ": "
                            + libraryPath);
        }
    }
}

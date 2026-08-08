package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Target-selectable texts emitted from one authoritative final LLVM module. */
public record NativeLlvmSource(
        String owner,
        Path retainedPath,
        Optional<Path> omissionPath,
        boolean omissionSafe,
        String proofReasonCode) {
    public NativeLlvmSource {
        Objects.requireNonNull(owner, "owner");
        retainedPath = normalize(retainedPath, "retainedPath");
        omissionPath = Objects.requireNonNull(omissionPath, "omissionPath")
                .map(path -> normalize(path, "omissionPath"));
        Objects.requireNonNull(proofReasonCode, "proofReasonCode");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("LLVM source owner must not be blank");
        }
        if (omissionSafe != omissionPath.isPresent()) {
            throw new IllegalArgumentException(
                    "an omission-safe LLVM source must have exactly one omission variant");
        }
        if (omissionPath.filter(retainedPath::equals).isPresent()) {
            throw new IllegalArgumentException(
                    "retained and omission-safe LLVM source paths must differ");
        }
    }

    public static NativeLlvmSource unmodeled(Path retainedPath) {
        Path normalized = normalize(retainedPath, "retainedPath");
        return new NativeLlvmSource(
                normalized.toString().replace('\\', '/'),
                normalized,
                Optional.empty(),
                false,
                "LLVM_UNWIND_PROOF_UNAVAILABLE");
    }

    public Path select(NativeUnwindRetentionDecision targetDecision) {
        Objects.requireNonNull(targetDecision, "targetDecision");
        if (!targetDecision.effective() && omissionSafe) {
            return omissionPath.orElseThrow();
        }
        return retainedPath;
    }

    public boolean omitsUnwind(NativeUnwindRetentionDecision targetDecision) {
        return !targetDecision.effective() && omissionSafe;
    }

    private static Path normalize(Path path, String label) {
        return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
    }
}

package xyz.melodysky.toolchain;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable mapping from logical retained LLVM input to a proven target variant. */
public final class NativeLlvmSourcePlan {
    private final List<NativeLlvmSource> sources;
    private final Map<Path, NativeLlvmSource> byRetainedPath;

    public NativeLlvmSourcePlan(List<NativeLlvmSource> sources) {
        this.sources = Objects.requireNonNull(sources, "sources").stream()
                .filter(Objects::nonNull)
                .sorted(java.util.Comparator.comparing(source -> source.retainedPath().toString()))
                .toList();
        LinkedHashMap<Path, NativeLlvmSource> indexed = new LinkedHashMap<>();
        for (NativeLlvmSource source : this.sources) {
            if (indexed.put(source.retainedPath(), source) != null) {
                throw new IllegalArgumentException(
                        "duplicate retained LLVM source path: " + source.retainedPath());
            }
        }
        byRetainedPath = java.util.Collections.unmodifiableMap(indexed);
    }

    public static NativeLlvmSourcePlan retaining(List<Path> llvmSources) {
        return new NativeLlvmSourcePlan(Objects.requireNonNull(llvmSources, "llvmSources")
                .stream()
                .filter(Objects::nonNull)
                .map(NativeLlvmSource::unmodeled)
                .toList());
    }

    public List<NativeLlvmSource> sources() {
        return sources;
    }

    public List<Path> retainedPaths() {
        return sources.stream().map(NativeLlvmSource::retainedPath).toList();
    }

    public Path select(
            Path retainedPath,
            NativeUnwindRetentionDecision targetDecision) {
        return source(retainedPath).select(targetDecision);
    }

    public NativeLlvmSource source(Path retainedPath) {
        Path normalized = Objects.requireNonNull(retainedPath, "retainedPath")
                .toAbsolutePath()
                .normalize();
        NativeLlvmSource source = byRetainedPath.get(normalized);
        if (source == null) {
            throw new IllegalArgumentException(
                    "LLVM source is missing from unwind source plan: " + normalized);
        }
        return source;
    }

    public NativeLlvmUnwindTargetSummary summarize(
            NativeUnwindRetentionDecision generatedCDecision,
            int unmodeledObjectInputCount) {
        Objects.requireNonNull(generatedCDecision, "generatedCDecision");
        int omitted = (int) sources.stream()
                .filter(source -> source.omitsUnwind(generatedCDecision))
                .count();
        int retained = sources.size() - omitted;
        boolean finalOmissionExpected = !generatedCDecision.effective()
                && retained == 0
                && unmodeledObjectInputCount == 0;
        NativeUnwindRetentionReason reason;
        if (generatedCDecision.effective()) {
            reason = generatedCDecision.reason();
        } else if (unmodeledObjectInputCount > 0) {
            reason = NativeUnwindRetentionReason.UNMODELED_OBJECT_INPUT_RETAINED;
        } else if (retained > 0) {
            reason = NativeUnwindRetentionReason.LLVM_MODULE_PROOF_RETAINED;
        } else {
            reason = NativeUnwindRetentionReason.CONFIG_DISABLED;
        }
        return new NativeLlvmUnwindTargetSummary(
                generatedCDecision,
                sources.size(),
                omitted,
                retained,
                unmodeledObjectInputCount,
                finalOmissionExpected,
                !finalOmissionExpected,
                reason);
    }
}

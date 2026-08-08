package xyz.melodysky.backend.llvm.model;

import java.util.Objects;

/**
 * Binds one canonical final module to the proof computed for that exact model.
 *
 * <p>Callers cannot combine a proof from one module with another module when
 * requesting the unwind-omitting text variant.</p>
 */
public final class LlvmModuleEmissionPlan {
    private final LlvmModule module;
    private final LlvmNativeUnwindProof proof;

    private LlvmModuleEmissionPlan(
            LlvmModule module,
            LlvmNativeUnwindProof proof) {
        this.module = Objects.requireNonNull(module, "module");
        this.proof = Objects.requireNonNull(proof, "proof");
    }

    public static LlvmModuleEmissionPlan create(LlvmModule module) {
        Objects.requireNonNull(module, "module");
        return new LlvmModuleEmissionPlan(
                module,
                new LlvmNativeUnwindAnalyzer().analyze(module));
    }

    public LlvmModule module() {
        return module;
    }

    public LlvmNativeUnwindProof proof() {
        return proof;
    }
}

package xyz.melodysky.backend.llvm.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Proves native-unwind absence from the final LLVM model only.
 *
 * <p>This analyzer intentionally never reads emitted LLVM text. Opaque or
 * forgotten model metadata is an incomplete proof and therefore fails closed.</p>
 */
public final class LlvmNativeUnwindAnalyzer {
    public LlvmNativeUnwindProof analyze(LlvmModule module) {
        Objects.requireNonNull(module, "module");
        ArrayList<LlvmNativeUnwindFinding> findings = new ArrayList<>();
        for (LlvmFunction function : module.functions()) {
            if (function.nativeUnwindSemantics()
                    != LlvmNativeUnwindSemantics.PROVEN_ABSENT) {
                findings.add(LlvmNativeUnwindFinding.function(
                        function.name(),
                        function.nativeUnwindSemantics()));
            }
            for (LlvmBasicBlock block : function.blocks()) {
                for (int index = 0; index < block.instructions().size(); index++) {
                    LlvmInstruction instruction = block.instructions().get(index);
                    if (instruction.nativeUnwindSemantics()
                            != LlvmNativeUnwindSemantics.PROVEN_ABSENT) {
                        findings.add(LlvmNativeUnwindFinding.instruction(
                                function.name(),
                                block.name(),
                                index,
                                instruction.nativeUnwindSemantics()));
                    }
                }
            }
        }
        if (findings.isEmpty()) {
            return new LlvmNativeUnwindProof(
                    true,
                    LlvmNativeUnwindProof.PROVEN_ABSENT,
                    List.of());
        }
        boolean required = findings.stream().anyMatch(finding ->
                finding.semantics() == LlvmNativeUnwindSemantics.REQUIRED);
        return new LlvmNativeUnwindProof(
                false,
                required
                        ? LlvmNativeUnwindProof.REQUIRED
                        : LlvmNativeUnwindProof.PROOF_INCOMPLETE,
                findings);
    }
}

package xyz.melodysky.toolchain;

import java.util.List;
/**
 * Bounded wrapper-topology policy derived from final native implementation
 * facts.
 *
 * <p>LLVM bodies that consume JVM/JNI state use the branched topology so a
 * Java binding does not expose a one-hop wrapper-to-implementation edge.
 * Pure native-scalar bodies retain the cheaper build-diverse topology set.</p>
 */
enum NativeLocalAbiProfile {
    COMPACT_DIVERSE(List.of(NativeLocalAbiPlan.Shape.values())),
    JVM_SEMANTIC_SURFACE(List.of(
            NativeLocalAbiPlan.Shape.BRANCHED_PERMUTING_BRIDGE));

    private final List<NativeLocalAbiPlan.Shape> candidateShapes;

    NativeLocalAbiProfile(
            List<NativeLocalAbiPlan.Shape> candidateShapes) {
        this.candidateShapes = List.copyOf(candidateShapes);
        if (this.candidateShapes.isEmpty()) {
            throw new IllegalArgumentException(
                    "native local ABI profile must have a candidate shape");
        }
    }

    static NativeLocalAbiProfile forAbi(
            boolean passesJniEnv,
            boolean passesOwnerClass) {
        return passesJniEnv || passesOwnerClass
                ? JVM_SEMANTIC_SURFACE
                : COMPACT_DIVERSE;
    }

    List<NativeLocalAbiPlan.Shape> candidateShapes() {
        return candidateShapes;
    }
}

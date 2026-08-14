package xyz.melodysky.toolchain;

import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;

/** Selects the mandatory branched topology for JVM/JNI semantic surfaces. */
final class NativeJniEntrySemanticSurface {
    private NativeJniEntrySemanticSurface() {}

    static boolean requiresBranchedTopology(
            NativeMethodImplementation implementation,
            IrMethod method,
            NativeLocalReferencePlan localReferences) {
        if (implementation.passesJniEnv()
                || implementation.passesOwnerClass()
                || NativeJniEntryDescriptorPolicy.hasReferenceSurface(
                        implementation.decision().method().descriptor())
                || NativeJniEntryImplementationFacts.hasRuntimeMetadata(
                        implementation)
                || NativeJniEntryLocalReferenceFacts
                        .requiresSemanticHandling(localReferences)) {
            return true;
        }
        if (method == null) {
            return false;
        }
        return method.parameters().stream()
                        .anyMatch(value -> value.type() == IrType.REFERENCE)
                || method.returnType() == IrType.REFERENCE
                || method.blocks().stream().anyMatch(block ->
                        !block.exceptionCatchTypes().isEmpty()
                                || !block.exceptionEdges().isEmpty()
                                || block.terminator().kind()
                                        == IrTerminatorKind.THROW
                                || block.instructions().stream().anyMatch(
                                        instruction ->
                                                !instruction.exceptionSites()
                                                        .isEmpty()
                                                        || instruction.result()
                                                                .map(value ->
                                                                        value.type()
                                                                                == IrType.REFERENCE)
                                                                .orElse(false)
                                                        || instruction.operands()
                                                                .stream()
                                                                .anyMatch(value ->
                                                                        value.type()
                                                                                == IrType.REFERENCE)));
    }
}

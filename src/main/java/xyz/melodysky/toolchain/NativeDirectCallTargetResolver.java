package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** Resolves the closed same-owner subset eligible for direct LLVM calls. */
final class NativeDirectCallTargetResolver {
    private final NativeLocalReferenceSafety localReferenceSafety =
            new NativeLocalReferenceSafety();

    List<String> directTargets(
            IrMethod method,
            Set<String> supportedLlvmMethods,
            Map<String, IrMethod> nativeBodies,
            Set<String> compilerInternalMethodKeys) {
        return sameOwnerTargets(
                        method,
                        supportedLlvmMethods,
                        nativeBodies,
                        compilerInternalMethodKeys)
                .stream()
                .sorted()
                .toList();
    }

    Set<String> sameOwnerTargets(
            IrMethod method,
            Set<String> supportedLlvmMethods,
            Map<String, IrMethod> nativeBodies,
            Set<String> compilerInternalMethodKeys) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction ->
                        instruction.opcode() == IrOpcode.CALL_STATIC
                                || instruction.opcode() == IrOpcode.CALL_DIRECT
                                || isDirectSpecialCall(instruction))
                .map(instruction -> instruction.symbol().orElseThrow())
                .filter(supportedLlvmMethods::contains)
                .filter(target -> target.startsWith(method.owner() + "#"))
                .filter(target -> safeReferenceBoundary(
                        target,
                        nativeBodies,
                        compilerInternalMethodKeys))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private boolean safeReferenceBoundary(
            String target,
            Map<String, IrMethod> nativeBodies,
            Set<String> compilerInternalMethodKeys) {
        IrMethod body = nativeBodies.get(target);
        if (compilerInternalMethodKeys.contains(target)) {
            return body != null
                    && body.returnType() != IrType.REFERENCE
                    && !localReferenceSafety.createsOwnedLocalReference(body);
        }
        return body == null
                || (body.returnType() != IrType.REFERENCE
                        && !localReferenceSafety.createsOwnedLocalReference(body));
    }

    private boolean isDirectSpecialCall(
            xyz.melodysky.ir.model.IrInstruction instruction) {
        return instruction.opcode() == IrOpcode.CALL_SPECIAL
                && instruction.symbol()
                        .map(symbol -> !symbol.contains("#<init>!"))
                        .orElse(false);
    }
}

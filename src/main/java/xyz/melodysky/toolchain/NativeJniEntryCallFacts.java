package xyz.melodysky.toolchain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.ir.model.IrMethod;

/**
 * Immutable inbound native-call facts shared by proxy planning and
 * final-plan validation.
 */
final class NativeJniEntryCallFacts {
    private final Set<String> nativeCallTargets;

    private NativeJniEntryCallFacts(Set<String> nativeCallTargets) {
        this.nativeCallTargets = Set.copyOf(nativeCallTargets);
    }

    static NativeJniEntryCallFacts analyze(
            List<NativeMethodImplementation> implementations,
            Map<String, IrMethod> irMethods) {
        Objects.requireNonNull(implementations, "implementations");
        Objects.requireNonNull(irMethods, "irMethods");
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (NativeMethodImplementation implementation : implementations) {
            targets.addAll(implementation.directCallTargets());
            targets.addAll(implementation.staticCallKeys());
            targets.addAll(implementation.dispatchKeys());
            targets.addAll(implementation.constructorCallKeys());
            if (!implementation.emitsStandaloneLlvmBody()) {
                continue;
            }
            IrMethod method = implementation.implementationIrMethod()
                    .orElse(irMethods.get(implementation.methodKey()));
            if (method == null) {
                continue;
            }
            method.blocks().stream()
                    .flatMap(block -> block.instructions().stream())
                    .flatMap(instruction -> instruction.symbol().stream())
                    .filter(NativeJniEntryCallFacts::looksLikeMethodKey)
                    .forEach(targets::add);
        }
        return new NativeJniEntryCallFacts(targets);
    }

    boolean targets(String methodKey) {
        return nativeCallTargets.contains(
                Objects.requireNonNull(methodKey, "methodKey"));
    }

    private static boolean looksLikeMethodKey(String symbol) {
        int ownerEnd = symbol.indexOf('#');
        int descriptorStart = symbol.indexOf('!');
        return ownerEnd > 0 && descriptorStart > ownerEnd;
    }
}

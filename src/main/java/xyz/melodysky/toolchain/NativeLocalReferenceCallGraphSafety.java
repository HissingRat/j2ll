package xyz.melodysky.toolchain;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import xyz.melodysky.ir.model.IrMethod;

/**
 * Propagates owned JNI local-reference creation through direct LLVM calls.
 *
 * <p>An internal LLVM function does not create a new JNI local-reference
 * frame. Consequently, a caller loop that repeatedly invokes a void or
 * primitive-returning callee can leak the callee's local references even
 * though neither the call result nor the callee CFG exposes a local cycle.
 * Direct-call recursion has the same property. This analysis freezes those
 * facts before native implementation planning and fails the affected roots
 * closed until ownership-aware last-use deletion is available.</p>
 */
public final class NativeLocalReferenceCallGraphSafety {
    private final NativeLocalReferenceSafety methodSafety =
            new NativeLocalReferenceSafety();

    public NativeLocalReferenceCallGraphAnalysis analyze(
            Map<String, IrMethod> methods,
            Set<String> directCallClosure) {
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(directCallClosure, "directCallClosure");

        LinkedHashMap<String, IrMethod> analyzedMethods =
                analyzedMethods(methods, directCallClosure);
        LinkedHashMap<String, Set<String>> calls = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> cyclicCalls =
                new LinkedHashMap<>();
        LinkedHashSet<String> producing = new LinkedHashSet<>();
        LinkedHashSet<String> unbounded = new LinkedHashSet<>();

        for (Map.Entry<String, IrMethod> entry : analyzedMethods.entrySet()) {
            String methodKey = entry.getKey();
            IrMethod method = entry.getValue();
            calls.put(
                    methodKey,
                    directTargets(
                            method,
                            methodSafety.reachableDirectCallTargets(method),
                            analyzedMethods.keySet()));
            cyclicCalls.put(
                    methodKey,
                    directTargets(
                            method,
                            methodSafety.directCallTargetsInCycles(method),
                            analyzedMethods.keySet()));
            if (methodSafety.createsOwnedLocalReference(method)) {
                producing.add(methodKey);
            }
            if (methodSafety.hasUnboundedLocalReferenceRisk(method)) {
                unbounded.add(methodKey);
            }
        }

        propagateReferenceProduction(calls, producing);
        for (String methodKey : analyzedMethods.keySet()) {
            if (cyclicCalls.getOrDefault(methodKey, Set.of()).stream()
                    .anyMatch(producing::contains)) {
                unbounded.add(methodKey);
            }
            if (producing.contains(methodKey)
                    && participatesInCallCycle(methodKey, calls)) {
                unbounded.add(methodKey);
            }
        }
        propagateUnboundedExecution(calls, unbounded);
        return new NativeLocalReferenceCallGraphAnalysis(
                producing,
                unbounded);
    }

    private LinkedHashMap<String, IrMethod> analyzedMethods(
            Map<String, IrMethod> methods,
            Set<String> directCallClosure) {
        LinkedHashMap<String, IrMethod> result = new LinkedHashMap<>();
        directCallClosure.stream()
                .sorted()
                .filter(methods::containsKey)
                .forEach(methodKey ->
                        result.put(methodKey, methods.get(methodKey)));
        return result;
    }

    private Set<String> directTargets(
            IrMethod caller,
            Set<String> candidates,
            Set<String> analyzedMethodKeys) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        candidates.stream()
                .filter(analyzedMethodKeys::contains)
                .filter(target -> target.startsWith(caller.owner() + "#"))
                .sorted()
                .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private void propagateReferenceProduction(
            Map<String, Set<String>> calls,
            Set<String> producing) {
        boolean changed;
        do {
            changed = false;
            for (String methodKey : calls.keySet()) {
                if (!producing.contains(methodKey)
                        && calls.get(methodKey).stream()
                                .anyMatch(producing::contains)) {
                    producing.add(methodKey);
                    changed = true;
                }
            }
        } while (changed);
    }

    private void propagateUnboundedExecution(
            Map<String, Set<String>> calls,
            Set<String> unbounded) {
        boolean changed;
        do {
            changed = false;
            for (String methodKey : calls.keySet()) {
                if (!unbounded.contains(methodKey)
                        && calls.get(methodKey).stream()
                                .anyMatch(unbounded::contains)) {
                    unbounded.add(methodKey);
                    changed = true;
                }
            }
        } while (changed);
    }

    private boolean participatesInCallCycle(
            String start,
            Map<String, Set<String>> calls) {
        ArrayDeque<String> work = new ArrayDeque<>(
                calls.getOrDefault(start, Set.of()));
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        while (!work.isEmpty()) {
            String current = work.removeFirst();
            if (current.equals(start)) {
                return true;
            }
            if (visited.add(current)) {
                calls.getOrDefault(current, Set.of())
                        .forEach(work::addLast);
            }
        }
        return false;
    }
}

package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/** Mutable traversal state scoped to one evidence-collection activation. */
final class NativeImplementationEvidenceAccumulator {
    final TreeSet<String> fieldKeys = new TreeSet<>();
    final List<String> directCallTargets;
    final TreeSet<String> allocationKeys = new TreeSet<>();
    final TreeSet<String> typeCheckKeys = new TreeSet<>();
    final TreeSet<String> classObjectKeys = new TreeSet<>();
    final TreeSet<String> runtimeMetadataKeys = new TreeSet<>();
    final TreeSet<String> constructorCallKeys = new TreeSet<>();
    final TreeSet<String> staticCallKeys = new TreeSet<>();
    final TreeSet<String> dispatchKeys = new TreeSet<>();
    final TreeSet<String> stringHelperSymbols = new TreeSet<>();
    final HashSet<String> directNullValues = new HashSet<>();
    final ArrayList<List<String>> referenceComparisons = new ArrayList<>();
    boolean jdkScalarHelper;
    boolean allocationHelper;
    boolean typeHelper;
    boolean constructorCallHelper;
    boolean arithmeticExceptionHelper;
    boolean jvmNumericHelper;
    boolean arrayHelper;
    boolean arraycopyHelper;
    boolean unsafeHelper;
    boolean varHandleHelper;
    boolean lambdaHelper;
    boolean monitorHelper;
    boolean exceptionHelper;
    boolean runtimeMetadataHelper;
    boolean passesJniEnv;
    boolean passesOwnerClass;

    NativeImplementationEvidenceAccumulator(List<String> directCallTargets) {
        this.directCallTargets = List.copyOf(directCallTargets);
        this.passesOwnerClass = !directCallTargets.isEmpty();
    }

    void addCatchType(String catchType) {
        if (!catchType.equals("<any>")) {
            typeCheckKeys.add("instanceof:" + catchType);
        }
    }

    NativeMethodImplementationEvidence freeze() {
        return new NativeMethodImplementationEvidence(
                List.copyOf(fieldKeys),
                directCallTargets,
                List.copyOf(allocationKeys),
                List.copyOf(typeCheckKeys),
                List.copyOf(classObjectKeys),
                List.copyOf(runtimeMetadataKeys),
                List.copyOf(constructorCallKeys),
                List.copyOf(staticCallKeys),
                List.copyOf(dispatchKeys),
                List.copyOf(stringHelperSymbols),
                jdkScalarHelper,
                allocationHelper,
                typeHelper,
                constructorCallHelper,
                arithmeticExceptionHelper,
                jvmNumericHelper,
                arrayHelper,
                arraycopyHelper,
                unsafeHelper,
                varHandleHelper,
                lambdaHelper,
                monitorHelper,
                exceptionHelper,
                runtimeMetadataHelper,
                passesJniEnv,
                passesOwnerClass);
    }
}

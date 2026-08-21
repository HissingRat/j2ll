package xyz.melodysky.toolchain;

import java.util.List;

/** Immutable per-method evidence consumed by final native implementation planning. */
record NativeMethodImplementationEvidence(
        List<String> fieldKeys,
        List<String> directCallTargets,
        List<String> allocationKeys,
        List<String> typeCheckKeys,
        List<String> classObjectKeys,
        List<String> runtimeMetadataKeys,
        List<String> constructorCallKeys,
        List<String> staticCallKeys,
        List<String> dispatchKeys,
        List<String> stringHelperSymbols,
        boolean jdkScalarHelper,
        boolean allocationHelper,
        boolean typeHelper,
        boolean constructorCallHelper,
        boolean arithmeticExceptionHelper,
        boolean jvmNumericHelper,
        boolean arrayHelper,
        boolean arraycopyHelper,
        boolean unsafeHelper,
        boolean varHandleHelper,
        boolean lambdaHelper,
        boolean monitorHelper,
        boolean exceptionHelper,
        boolean runtimeMetadataHelper,
        boolean passesJniEnv,
        boolean passesOwnerClass) {
    NativeMethodImplementationEvidence {
        fieldKeys = List.copyOf(fieldKeys);
        directCallTargets = List.copyOf(directCallTargets);
        allocationKeys = List.copyOf(allocationKeys);
        typeCheckKeys = List.copyOf(typeCheckKeys);
        classObjectKeys = List.copyOf(classObjectKeys);
        runtimeMetadataKeys = List.copyOf(runtimeMetadataKeys);
        constructorCallKeys = List.copyOf(constructorCallKeys);
        staticCallKeys = List.copyOf(staticCallKeys);
        dispatchKeys = List.copyOf(dispatchKeys);
        stringHelperSymbols = List.copyOf(stringHelperSymbols);
    }

    NativeImplementationReasonClassifier.Facts reasonFacts(
            boolean synchronizedMethod) {
        return new NativeImplementationReasonClassifier.Facts(
                fieldKeys,
                directCallTargets,
                staticCallKeys,
                dispatchKeys,
                stringHelperSymbols,
                jdkScalarHelper,
                allocationHelper,
                typeHelper,
                constructorCallHelper,
                arithmeticExceptionHelper,
                jvmNumericHelper,
                arrayHelper,
                arraycopyHelper,
                varHandleHelper,
                lambdaHelper,
                unsafeHelper,
                monitorHelper,
                exceptionHelper,
                runtimeMetadataHelper,
                synchronizedMethod);
    }
}

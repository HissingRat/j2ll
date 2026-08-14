package xyz.melodysky.toolchain;

/** Shared final-plan interpretation of runtime-semantic implementation data. */
final class NativeJniEntryImplementationFacts {
    private NativeJniEntryImplementationFacts() {}

    static boolean hasRuntimeMetadata(
            NativeMethodImplementation implementation) {
        return !implementation.fieldKeys().isEmpty()
                || !implementation.directCallTargets().isEmpty()
                || !implementation.allocationKeys().isEmpty()
                || !implementation.typeCheckKeys().isEmpty()
                || !implementation.classObjectKeys().isEmpty()
                || !implementation.runtimeMetadataKeys().isEmpty()
                || !implementation.constructorCallKeys().isEmpty()
                || !implementation.staticCallKeys().isEmpty()
                || !implementation.dispatchKeys().isEmpty()
                || !implementation.stringHelperSymbols().isEmpty()
                || implementation.initializerPlan().isPresent();
    }
}

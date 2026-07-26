package xyz.melodysky.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import xyz.melodysky.analysis.field.NativeFieldInternalizationPlan;
import xyz.melodysky.packaging.FallbackSidecarFieldAccess;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/**
 * Attaches exact fallback-sidecar access metadata after the final native
 * implementation paths are known.
 */
public final class FallbackSidecarNativePlanReconciler {
    private static final String EMBEDDED_FALLBACK_REASON =
            "NATIVE_EMBEDDED_CLASS_BLOB_FALLBACK";

    public NativeImplementationPlan reconcile(
            NativeImplementationPlan implementationPlan,
            NativeFieldInternalizationPlan fieldPlan) {
        ArrayList<NativeMethodImplementation> reconciled = new ArrayList<>();
        for (NativeMethodImplementation implementation
                : implementationPlan.implementations()) {
            if (!isEmbeddedFallback(implementation)) {
                reconciled.add(implementation);
                continue;
            }
            List<FallbackSidecarFieldAccess> accesses =
                    FallbackSidecarFieldAccess.forMethod(
                            fieldPlan,
                            implementation.methodKey());
            if (accesses.isEmpty()) {
                reconciled.add(implementation);
                continue;
            }
            if (!implementation.decision().method().accessFlags().isStatic()) {
                throw new IllegalArgumentException(
                        "fallback sidecar access requires a static method: "
                                + implementation.methodKey());
            }
            LinkedHashSet<String> fieldKeys =
                    new LinkedHashSet<>(implementation.fieldKeys());
            for (FallbackSidecarFieldAccess access : accesses) {
                fieldKeys.add(access.nativeSlotMarker());
                fieldKeys.add(access.marker());
            }
            reconciled.add(copyWithFieldKeys(
                    implementation,
                    fieldKeys.stream().sorted().toList()));
        }
        return new NativeImplementationPlan(reconciled);
    }

    private boolean isEmbeddedFallback(
            NativeMethodImplementation implementation) {
        return implementation.path()
                        == NativeImplementationPath.TEMPLATE_JNI_PATH
                && implementation.reasonCode().equals(
                        EMBEDDED_FALLBACK_REASON);
    }

    private NativeMethodImplementation copyWithFieldKeys(
            NativeMethodImplementation implementation,
            List<String> fieldKeys) {
        return new NativeMethodImplementation(
                implementation.entry(),
                implementation.decision(),
                implementation.path(),
                implementation.llvmFunctionSymbol(),
                implementation.reasonCode(),
                implementation.passesJniEnv(),
                implementation.passesOwnerClass(),
                fieldKeys,
                implementation.directCallTargets(),
                implementation.allocationKeys(),
                implementation.typeCheckKeys(),
                implementation.classObjectKeys(),
                implementation.runtimeMetadataKeys(),
                implementation.constructorCallKeys(),
                implementation.staticCallKeys(),
                implementation.dispatchKeys(),
                implementation.stringHelperSymbols(),
                implementation.templateIrMethod());
    }
}

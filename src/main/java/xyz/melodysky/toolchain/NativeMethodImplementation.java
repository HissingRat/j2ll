package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.backend.llvm.LlvmFunctionAbi;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;

public record NativeMethodImplementation(
        NativeRegistrationEntry entry,
        MethodRewriteDecision decision,
        NativeImplementationPath path,
        Optional<String> llvmFunctionSymbol,
        String reasonCode,
        boolean passesJniEnv,
        boolean passesOwnerClass,
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
        Optional<IrMethod> templateIrMethod,
        Optional<InitializerImplementationPlan> initializerPlan,
        Optional<String> coalescedIntoMethodKey) implements Comparable<NativeMethodImplementation> {
    public NativeMethodImplementation {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(llvmFunctionSymbol, "llvmFunctionSymbol");
        Objects.requireNonNull(reasonCode, "reasonCode");
        fieldKeys = List.copyOf(Objects.requireNonNull(fieldKeys, "fieldKeys"));
        directCallTargets = List.copyOf(Objects.requireNonNull(directCallTargets, "directCallTargets"));
        allocationKeys = List.copyOf(Objects.requireNonNull(allocationKeys, "allocationKeys"));
        typeCheckKeys = List.copyOf(Objects.requireNonNull(typeCheckKeys, "typeCheckKeys"));
        classObjectKeys = List.copyOf(Objects.requireNonNull(classObjectKeys, "classObjectKeys"));
        runtimeMetadataKeys = List.copyOf(Objects.requireNonNull(runtimeMetadataKeys, "runtimeMetadataKeys"));
        constructorCallKeys = List.copyOf(Objects.requireNonNull(constructorCallKeys, "constructorCallKeys"));
        staticCallKeys = List.copyOf(Objects.requireNonNull(staticCallKeys, "staticCallKeys"));
        dispatchKeys = List.copyOf(Objects.requireNonNull(dispatchKeys, "dispatchKeys"));
        stringHelperSymbols = List.copyOf(Objects.requireNonNull(stringHelperSymbols, "stringHelperSymbols"));
        Objects.requireNonNull(templateIrMethod, "templateIrMethod");
        Objects.requireNonNull(initializerPlan, "initializerPlan");
        coalescedIntoMethodKey = Objects.requireNonNull(
                coalescedIntoMethodKey,
                "coalescedIntoMethodKey");
        if (initializerPlan.isPresent()
                && path != NativeImplementationPath.LLVM_NATIVE_PATH) {
            throw new IllegalArgumentException(
                    "initializer implementation plans require the LLVM native path");
        }
        if (coalescedIntoMethodKey.isPresent()) {
            if (path != NativeImplementationPath.LLVM_NATIVE_PATH
                    || decision.strategy()
                            != xyz.melodysky.packaging.MethodRewriteStrategy
                                    .INTERNAL_NATIVE_ONLY) {
                throw new IllegalArgumentException(
                        "only LLVM internal-native-only methods may be coalesced");
            }
            if (coalescedIntoMethodKey.orElseThrow()
                    .equals(decision.method().methodKey())) {
                throw new IllegalArgumentException(
                        "native-only method cannot be coalesced into itself");
            }
        }
    }

    public NativeMethodImplementation(
            NativeRegistrationEntry entry,
            MethodRewriteDecision decision,
            NativeImplementationPath path,
            Optional<String> llvmFunctionSymbol,
            String reasonCode,
            boolean passesJniEnv,
            boolean passesOwnerClass,
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
            Optional<IrMethod> templateIrMethod,
            Optional<InitializerImplementationPlan> initializerPlan) {
        this(
                entry,
                decision,
                path,
                llvmFunctionSymbol,
                reasonCode,
                passesJniEnv,
                passesOwnerClass,
                fieldKeys,
                directCallTargets,
                allocationKeys,
                typeCheckKeys,
                classObjectKeys,
                runtimeMetadataKeys,
                constructorCallKeys,
                staticCallKeys,
                dispatchKeys,
                stringHelperSymbols,
                templateIrMethod,
                initializerPlan,
                Optional.empty());
    }

    public NativeMethodImplementation(
            NativeRegistrationEntry entry,
            MethodRewriteDecision decision,
            NativeImplementationPath path,
            Optional<String> llvmFunctionSymbol,
            String reasonCode,
            boolean passesJniEnv,
            boolean passesOwnerClass,
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
            Optional<IrMethod> templateIrMethod) {
        this(
                entry,
                decision,
                path,
                llvmFunctionSymbol,
                reasonCode,
                passesJniEnv,
                passesOwnerClass,
                fieldKeys,
                directCallTargets,
                allocationKeys,
                typeCheckKeys,
                classObjectKeys,
                runtimeMetadataKeys,
                constructorCallKeys,
                staticCallKeys,
                dispatchKeys,
                stringHelperSymbols,
                templateIrMethod,
                Optional.empty(),
                Optional.empty());
    }

    public NativeMethodImplementation(
            NativeRegistrationEntry entry,
            MethodRewriteDecision decision,
            NativeImplementationPath path,
            Optional<String> llvmFunctionSymbol,
            String reasonCode) {
        this(
                entry,
                decision,
                path,
                llvmFunctionSymbol,
                reasonCode,
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public String methodKey() {
        return decision.method().methodKey();
    }

    public LlvmFunctionAbi llvmFunctionAbi() {
        return LlvmFunctionAbi.semanticInternal(
                passesJniEnv,
                passesOwnerClass);
    }

    public NativeMethodImplementation withDecision(
            MethodRewriteDecision replacement) {
        return new NativeMethodImplementation(
                entry,
                replacement,
                path,
                llvmFunctionSymbol,
                reasonCode,
                passesJniEnv,
                passesOwnerClass,
                fieldKeys,
                directCallTargets,
                allocationKeys,
                typeCheckKeys,
                classObjectKeys,
                runtimeMetadataKeys,
                constructorCallKeys,
                staticCallKeys,
                dispatchKeys,
                stringHelperSymbols,
                templateIrMethod,
                initializerPlan,
                coalescedIntoMethodKey);
    }

    public boolean emitsStandaloneLlvmBody() {
        return path == NativeImplementationPath.LLVM_NATIVE_PATH
                && coalescedIntoMethodKey.isEmpty();
    }

    public NativeMethodImplementation coalescedInto(String callerMethodKey) {
        return new NativeMethodImplementation(
                entry,
                decision,
                path,
                llvmFunctionSymbol,
                "LLVM_NATIVE_ONLY_BODY_COALESCED",
                passesJniEnv,
                passesOwnerClass,
                fieldKeys,
                directCallTargets,
                allocationKeys,
                typeCheckKeys,
                classObjectKeys,
                runtimeMetadataKeys,
                constructorCallKeys,
                staticCallKeys,
                dispatchKeys,
                stringHelperSymbols,
                templateIrMethod,
                initializerPlan,
                Optional.of(callerMethodKey));
    }

    /** Implementation IR snapshot used by generated-runtime reachability. */
    public Optional<IrMethod> implementationIrMethod() {
        return templateIrMethod;
    }

    public NativeMethodImplementation withEffectiveIrMethod(
            IrMethod callerBody,
            String removedTargetMethodKey,
            LlvmFunctionAbi updatedAbi) {
        Objects.requireNonNull(updatedAbi, "updatedAbi");
        return new NativeMethodImplementation(
                entry,
                decision,
                path,
                llvmFunctionSymbol,
                reasonCode,
                updatedAbi.passesJniEnv(),
                updatedAbi.passesOwnerClass(),
                fieldKeys,
                directCallTargets.stream()
                        .filter(target -> !target.equals(removedTargetMethodKey))
                        .toList(),
                allocationKeys,
                typeCheckKeys,
                classObjectKeys,
                runtimeMetadataKeys,
                constructorCallKeys,
                staticCallKeys.stream()
                        .filter(target -> !target.equals(removedTargetMethodKey))
                        .toList(),
                dispatchKeys.stream()
                        .filter(target -> !target.equals(removedTargetMethodKey))
                        .toList(),
                stringHelperSymbols,
                Optional.of(callerBody),
                initializerPlan,
                coalescedIntoMethodKey);
    }

    @Override
    public int compareTo(NativeMethodImplementation other) {
        return entry.compareTo(other.entry);
    }
}

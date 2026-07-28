package xyz.melodysky.toolchain;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
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
        Optional<InitializerImplementationPlan> initializerPlan) implements Comparable<NativeMethodImplementation> {
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
        if (initializerPlan.isPresent()
                && path != NativeImplementationPath.LLVM_NATIVE_PATH) {
            throw new IllegalArgumentException(
                    "initializer implementation plans require the LLVM native path");
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
                Optional.empty());
    }

    public String methodKey() {
        return decision.method().methodKey();
    }

    public LlvmFunctionAbi llvmFunctionAbi() {
        return new LlvmFunctionAbi(passesJniEnv, passesOwnerClass);
    }

    @Override
    public int compareTo(NativeMethodImplementation other) {
        return entry.compareTo(other.entry);
    }
}

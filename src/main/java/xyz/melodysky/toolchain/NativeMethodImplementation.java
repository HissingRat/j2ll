package xyz.melodysky.toolchain;

import java.util.Objects;
import java.util.Optional;
import java.util.List;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.NativeRegistrationEntry;

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
        List<String> dispatchKeys,
        List<String> stringHelperSymbols,
        Optional<IrMethod> templateIrMethod) implements Comparable<NativeMethodImplementation> {
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
        dispatchKeys = List.copyOf(Objects.requireNonNull(dispatchKeys, "dispatchKeys"));
        stringHelperSymbols = List.copyOf(Objects.requireNonNull(stringHelperSymbols, "stringHelperSymbols"));
        Objects.requireNonNull(templateIrMethod, "templateIrMethod");
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
                Optional.empty());
    }

    public String methodKey() {
        return decision.method().methodKey();
    }

    @Override
    public int compareTo(NativeMethodImplementation other) {
        return entry.compareTo(other.entry);
    }
}

package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.backend.llvm.LlvmFunctionAbi;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.runtime.jni.JniTypeMapper;

/** Exact JVM physical-entry to semantic-LLVM parameter projection. */
record NativeJniProxyAbiProjection(
        LlvmType returnType,
        List<LlvmType> physicalParameterTypes,
        List<LlvmType> semanticParameterTypes,
        List<Integer> semanticFromPhysicalIndices) {
    NativeJniProxyAbiProjection {
        Objects.requireNonNull(returnType, "returnType");
        physicalParameterTypes = List.copyOf(Objects.requireNonNull(
                physicalParameterTypes,
                "physicalParameterTypes"));
        semanticParameterTypes = List.copyOf(Objects.requireNonNull(
                semanticParameterTypes,
                "semanticParameterTypes"));
        semanticFromPhysicalIndices = List.copyOf(Objects.requireNonNull(
                semanticFromPhysicalIndices,
                "semanticFromPhysicalIndices"));
        if (semanticParameterTypes.size()
                        != semanticFromPhysicalIndices.size()
                || new HashSet<>(semanticFromPhysicalIndices).size()
                        != semanticFromPhysicalIndices.size()) {
            throw new IllegalArgumentException(
                    "semantic JNI proxy projection must be one-to-one");
        }
        for (int index = 0; index < semanticParameterTypes.size(); index++) {
            int physicalIndex = semanticFromPhysicalIndices.get(index);
            if (physicalIndex < 0
                    || physicalIndex >= physicalParameterTypes.size()
                    || semanticParameterTypes.get(index)
                            != physicalParameterTypes.get(physicalIndex)) {
                throw new IllegalArgumentException(
                        "semantic JNI proxy projection has an invalid source");
            }
        }
    }

    static Optional<NativeJniProxyAbiProjection> derive(
            NativeMethodImplementation implementation) {
        Objects.requireNonNull(implementation, "implementation");
        var method = implementation.decision().method();
        LlvmFunctionAbi semanticAbi = implementation.llvmFunctionAbi();
        if (semanticAbi.isPhysicalJniEntry()
                || (!method.accessFlags().isStatic()
                        && semanticAbi.passesOwnerClass())
                || !NativeJniEntryDescriptorPolicy.supports(
                        method.descriptor())) {
            return Optional.empty();
        }

        JniTypeMapper typeMapper = new JniTypeMapper();
        Optional<LlvmType> returnType = NativeJniEntryDescriptorPolicy
                .llvmType(typeMapper.returnDescriptor(method.descriptor()), true);
        if (returnType.isEmpty()) {
            return Optional.empty();
        }
        ArrayList<LlvmType> physical = new ArrayList<>();
        physical.add(LlvmType.PTR); // JNIEnv*
        physical.add(LlvmType.PTR); // jclass or jobject
        for (String descriptor : typeMapper.parameterDescriptors(
                method.descriptor())) {
            Optional<LlvmType> type = NativeJniEntryDescriptorPolicy
                    .llvmType(descriptor, false);
            if (type.isEmpty()) {
                return Optional.empty();
            }
            physical.add(type.orElseThrow());
        }

        ArrayList<Integer> sources = new ArrayList<>();
        if (semanticAbi.passesJniEnv()) {
            sources.add(0);
        }
        if (semanticAbi.passesOwnerClass()) {
            sources.add(1);
        }
        if (!method.accessFlags().isStatic()) {
            sources.add(1);
        }
        for (int index = 2; index < physical.size(); index++) {
            sources.add(index);
        }
        List<LlvmType> semantic = sources.stream()
                .map(physical::get)
                .toList();
        return Optional.of(new NativeJniProxyAbiProjection(
                returnType.orElseThrow(),
                physical,
                semantic,
                sources));
    }

    int semanticParameterCount() {
        return semanticParameterTypes.size();
    }
}

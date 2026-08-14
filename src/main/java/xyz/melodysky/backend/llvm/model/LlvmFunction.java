package xyz.melodysky.backend.llvm.model;

import java.util.List;
import java.util.Objects;

public record LlvmFunction(
        String name,
        LlvmLinkage linkage,
        LlvmVisibility visibility,
        LlvmType returnType,
        List<LlvmParameter> parameters,
        List<LlvmBasicBlock> blocks,
        LlvmNativeUnwindSemantics nativeUnwindSemantics,
        List<LlvmFunctionAttribute> attributes) {
    public LlvmFunction {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(linkage, "linkage");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(returnType, "returnType");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        Objects.requireNonNull(nativeUnwindSemantics, "nativeUnwindSemantics");
        attributes = Objects.requireNonNull(attributes, "attributes")
                .stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    public LlvmFunction(
            String name,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            LlvmType returnType,
            List<LlvmParameter> parameters,
            List<LlvmBasicBlock> blocks,
            LlvmNativeUnwindSemantics nativeUnwindSemantics) {
        this(
                name,
                linkage,
                visibility,
                returnType,
                parameters,
                blocks,
                nativeUnwindSemantics,
                List.of());
    }

    public LlvmFunction(
            String name,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            LlvmType returnType,
            List<LlvmParameter> parameters,
            List<LlvmBasicBlock> blocks) {
        this(
                name,
                linkage,
                visibility,
                returnType,
                parameters,
                blocks,
                LlvmNativeUnwindSemantics.UNKNOWN,
                List.of());
    }

    public LlvmFunction withAttributes(
            List<LlvmFunctionAttribute> replacement) {
        return new LlvmFunction(
                name,
                linkage,
                visibility,
                returnType,
                parameters,
                blocks,
                nativeUnwindSemantics,
                replacement);
    }
}

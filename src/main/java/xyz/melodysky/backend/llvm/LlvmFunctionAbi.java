package xyz.melodysky.backend.llvm;

import java.util.Objects;

public record LlvmFunctionAbi(
        boolean passesJniEnv,
        boolean passesOwnerClass,
        Purpose purpose) {
    public LlvmFunctionAbi {
        Objects.requireNonNull(purpose, "purpose");
    }

    public static LlvmFunctionAbi semanticInternal(
            boolean passesJniEnv,
            boolean passesOwnerClass) {
        return new LlvmFunctionAbi(
                passesJniEnv,
                passesOwnerClass,
                Purpose.SEMANTIC_INTERNAL);
    }

    public static LlvmFunctionAbi physicalJniEntry(
            boolean staticMethod) {
        return new LlvmFunctionAbi(
                true,
                staticMethod,
                Purpose.PHYSICAL_JNI_ENTRY);
    }

    public boolean isPhysicalJniEntry() {
        return purpose == Purpose.PHYSICAL_JNI_ENTRY;
    }

    public enum Purpose {
        SEMANTIC_INTERNAL,
        PHYSICAL_JNI_ENTRY
    }
}

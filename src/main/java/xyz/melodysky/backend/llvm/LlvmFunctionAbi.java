package xyz.melodysky.backend.llvm;

public record LlvmFunctionAbi(
        boolean passesJniEnv,
        boolean passesOwnerClass) {
}

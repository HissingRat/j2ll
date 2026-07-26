package xyz.melodysky.toolchain;

/** Progress boundary for final per-owner LLVM compilation. */
public interface NativeLlvmCompilationListener {
    default void started(int totalOwners) {
    }

    default void moduleStarted(int currentOwner, int totalOwners, String owner) {
    }

    default void completed(int totalOwners) {
    }

    static NativeLlvmCompilationListener none() {
        return new NativeLlvmCompilationListener() {
        };
    }
}

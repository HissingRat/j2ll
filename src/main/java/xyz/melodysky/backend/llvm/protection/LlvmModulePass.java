package xyz.melodysky.backend.llvm.protection;

import xyz.melodysky.backend.llvm.model.LlvmModule;

public interface LlvmModulePass {
    String name();

    LlvmModule run(LlvmModule module, LlvmProtectionConfig config);
}

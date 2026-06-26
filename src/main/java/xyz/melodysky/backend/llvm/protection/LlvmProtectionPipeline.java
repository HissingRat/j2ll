package xyz.melodysky.backend.llvm.protection;

import java.util.List;
import xyz.melodysky.backend.llvm.model.LlvmModule;

public final class LlvmProtectionPipeline {
    private final List<LlvmModulePass> passes;

    public LlvmProtectionPipeline(List<LlvmModulePass> passes) {
        this.passes = List.copyOf(passes);
    }

    public static LlvmProtectionPipeline defaultPipeline() {
        return new LlvmProtectionPipeline(List.of(new LlvmNameObfuscationPass()));
    }

    public LlvmModule run(LlvmModule module, LlvmProtectionConfig config) {
        LlvmModule current = module;
        for (LlvmModulePass pass : passes) {
            current = pass.run(current, config);
        }
        return current;
    }
}

package xyz.melodysky.backend.llvm.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

class LlvmProtectionPipelineTest {
    @Test
    void disabledProtectionDoesNotRenameFunctions() {
        LlvmModule module = module();

        LlvmModule protectedModule = LlvmProtectionPipeline.defaultPipeline().run(module, LlvmProtectionConfig.disabled(1));

        assertEquals("j2ll_original", protectedModule.functions().get(0).name());
    }

    @Test
    void nameObfuscationIsDeterministicBySeed() {
        LlvmModule module = module();

        LlvmModule first = LlvmProtectionPipeline.defaultPipeline().run(module, LlvmProtectionConfig.enabled(1));
        LlvmModule second = LlvmProtectionPipeline.defaultPipeline().run(module, LlvmProtectionConfig.enabled(1));
        LlvmModule different = LlvmProtectionPipeline.defaultPipeline().run(module, LlvmProtectionConfig.enabled(2));

        assertEquals(first.functions().get(0).name(), second.functions().get(0).name());
        assertNotEquals(first.functions().get(0).name(), different.functions().get(0).name());
    }

    private LlvmModule module() {
        return new LlvmModule(
                "pkg/Sample",
                List.of(new LlvmFunction(
                        "j2ll_original",
                        LlvmLinkage.INTERNAL,
                        LlvmVisibility.HIDDEN,
                        LlvmType.VOID,
                        List.of(),
                        List.of(new LlvmBasicBlock(
                                "entry",
                                List.of(),
                                new LlvmTerminator(LlvmType.VOID, java.util.Optional.empty()))))));
    }
}

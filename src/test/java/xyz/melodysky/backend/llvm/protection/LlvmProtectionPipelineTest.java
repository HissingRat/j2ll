package xyz.melodysky.backend.llvm.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleValidator;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
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

    @Test
    void nameObfuscationPreservesExistingGlobals() {
        LlvmModule module = new LlvmModule(
                "pkg/Globals",
                List.of(),
                List.of(new LlvmGlobal("table", "internal constant [1 x i32] [i32 7], align 4")),
                module().functions());

        LlvmModule protectedModule =
                LlvmProtectionPipeline.defaultPipeline().run(module, LlvmProtectionConfig.enabled(1));

        assertEquals(module.globals(), protectedModule.globals());
    }

    @Test
    void combinedStructuralPipelineHonorsSwitchesAndEmitsValidatedGoldenLlvm() {
        LlvmModule module = structuralFixture();
        LlvmProtectionPipeline pipeline = structuralPipeline();
        LlvmModuleValidator validator = new LlvmModuleValidator();

        LlvmModule disabled =
                pipeline.run(module, LlvmProtectionConfig.disabled(73));
        assertSame(module, disabled);
        assertEquals(new LlvmTextEmitter().emit(module), new LlvmTextEmitter().emit(disabled));

        LlvmProtectionConfig enabled =
                LlvmProtectionConfig.selected(73, false, true, true, false, true);
        LlvmModule protectedModule = pipeline.run(module, enabled);

        assertTrue(validator.validate(module).isEmpty());
        assertTrue(validator.validate(protectedModule).isEmpty());
        assertEquals(
                List.of("entry", "merge", "right", "left"),
                protectedModule.functions().get(0).blocks().stream()
                        .map(LlvmBasicBlock::name)
                        .toList());
        assertEquals(
                List.of("j2ll_cit_beta", "llvm.used", "j2ll_cit_gamma", "j2ll_cit_alpha"),
                protectedModule.globals().stream().map(LlvmGlobal::name).toList());

        assertEquals("""
                ; ModuleID = 'fixture.pipeline'

                @j2ll_cit_beta = private constant [1 x ptr] [ptr @j2ll_pipeline], align 8

                @llvm.used = appending global [3 x ptr] [ptr @j2ll_cit_alpha, ptr @j2ll_cit_beta, ptr @j2ll_cit_gamma], section "llvm.metadata"

                @j2ll_cit_gamma = internal constant [1 x ptr] [ptr @j2ll_pipeline], align 8

                @j2ll_cit_alpha = internal constant [1 x ptr] [ptr @j2ll_pipeline], align 8

                define internal hidden i32 @j2ll_pipeline(i1 %flag) {
                entry:
                  %j2ll_opq_fffd6d0a9849a3794d8f_mix = xor i32 -1950314580, -1950314580
                  %j2ll_opq_fffd6d0a9849a3794d8f_true = icmp eq i32 %j2ll_opq_fffd6d0a9849a3794d8f_mix, 0
                  %j2ll_opq_fffd6d0a9849a3794d8f_condition = and i1 %flag, %j2ll_opq_fffd6d0a9849a3794d8f_true
                  br i1 %j2ll_opq_fffd6d0a9849a3794d8f_condition, label %left, label %right
                merge:
                  %selected = phi i32 [ 1, %left ], [ 2, %right ]
                  ret i32 %selected
                right:
                  br label %merge
                left:
                  br label %merge
                }
                """, new LlvmTextEmitter().emit(protectedModule));
    }

    private LlvmProtectionPipeline structuralPipeline() {
        return new LlvmProtectionPipeline(List.of(
                new LlvmBlockLayoutPerturbationPass(),
                new LlvmOpaquePredicatePass(),
                new LlvmGlobalLayoutPass()));
    }

    private LlvmModule structuralFixture() {
        LlvmFunction function = new LlvmFunction(
                "j2ll_pipeline",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(new LlvmParameter(LlvmType.I1, "%flag")),
                List.of(
                        new LlvmBasicBlock(
                                "entry",
                                List.of(),
                                LlvmTerminator.branch("%flag", "left", "right")),
                        new LlvmBasicBlock(
                                "left",
                                List.of(),
                                LlvmTerminator.gotoBlock("merge")),
                        new LlvmBasicBlock(
                                "right",
                                List.of(),
                                LlvmTerminator.gotoBlock("merge")),
                        new LlvmBasicBlock(
                                "merge",
                                List.of(xyz.melodysky.backend.llvm.model.LlvmInstruction.raw(
                                        Optional.of("%selected"),
                                        "phi i32 [ 1, %left ], [ 2, %right ]")),
                                new LlvmTerminator(LlvmType.I32, Optional.of("%selected")))));
        return new LlvmModule(
                "fixture.pipeline",
                List.of(),
                List.of(
                        new LlvmGlobal(
                                "j2ll_cit_alpha",
                                "internal constant [1 x ptr] [ptr @j2ll_pipeline], align 8"),
                        new LlvmGlobal(
                                "llvm.used",
                                "appending global [3 x ptr] [ptr @j2ll_cit_alpha, ptr @j2ll_cit_beta, ptr @j2ll_cit_gamma], section \"llvm.metadata\""),
                        new LlvmGlobal(
                                "j2ll_cit_beta",
                                "private constant [1 x ptr] [ptr @j2ll_pipeline], align 8"),
                        new LlvmGlobal(
                                "j2ll_cit_gamma",
                                "internal constant [1 x ptr] [ptr @j2ll_pipeline], align 8")),
                List.of(function));
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

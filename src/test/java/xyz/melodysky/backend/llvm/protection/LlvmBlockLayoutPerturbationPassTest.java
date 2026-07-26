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
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

class LlvmBlockLayoutPerturbationPassTest {
    @Test
    void disabledPassIsAnIdentityNoOp() {
        LlvmModule module = fixture();

        LlvmModule result =
                new LlvmBlockLayoutPerturbationPass().run(module, LlvmProtectionConfig.disabled(7));

        assertSame(module, result);
    }

    @Test
    void keepsEntryAndControlFlowReferencesWhileChangingEmissionOrder() {
        LlvmModule module = fixture();

        LlvmBlockLayoutPerturbationResult result = new LlvmBlockLayoutPerturbationPass()
                .runDetailed(
                        module,
                        LlvmProtectionConfig.selected(7, false, false, true, false, false));

        assertTrue(result.valid());
        assertEquals(List.of("f"), result.affectedFunctions());
        LlvmFunction before = module.functions().get(0);
        LlvmFunction after = result.module().functions().get(0);
        assertEquals("entry", after.blocks().get(0).name());
        assertNotEquals(blockNames(before), blockNames(after));
        assertEquals(before.blocks().get(0).terminator(), after.blocks().get(0).terminator());
        assertEquals(
                before.blocks().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                LlvmBasicBlock::name,
                                LlvmBasicBlock::terminator)),
                after.blocks().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                LlvmBasicBlock::name,
                                LlvmBasicBlock::terminator)));
    }

    @Test
    void sameSeedIsDeterministic() {
        LlvmModule module = fixture();
        LlvmProtectionConfig config =
                LlvmProtectionConfig.selected(19, false, false, true, false, false);

        LlvmModule first = new LlvmBlockLayoutPerturbationPass().run(module, config);
        LlvmModule second = new LlvmBlockLayoutPerturbationPass().run(module, config);

        assertEquals(first, second);
    }

    private List<String> blockNames(LlvmFunction function) {
        return function.blocks().stream().map(LlvmBasicBlock::name).toList();
    }

    private LlvmModule fixture() {
        LlvmFunction function = new LlvmFunction(
                "f",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(),
                List.of(
                        new LlvmBasicBlock(
                                "entry",
                                List.of(),
                                LlvmTerminator.branch("%condition", "left", "right")),
                        new LlvmBasicBlock(
                                "left",
                                List.of(),
                                LlvmTerminator.gotoBlock("exit")),
                        new LlvmBasicBlock(
                                "right",
                                List.of(),
                                LlvmTerminator.gotoBlock("exit")),
                        new LlvmBasicBlock(
                                "exit",
                                List.of(),
                                new LlvmTerminator(LlvmType.I32, Optional.of("0")))));
        return new LlvmModule("fixture", List.of(function));
    }
}

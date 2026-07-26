package xyz.melodysky.backend.llvm.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

class LlvmOpaquePredicatePassTest {
    @Test
    void disabledPassIsAnIdentityNoOp() {
        LlvmModule module = fixture();

        LlvmModule result = new LlvmOpaquePredicatePass()
                .run(module, LlvmProtectionConfig.disabled(13));

        assertSame(module, result);
    }

    @Test
    void strengthensBranchWithDefinedIntegerOperations() {
        LlvmModule module = fixture();

        LlvmOpaquePredicateResult result = new LlvmOpaquePredicatePass()
                .runDetailed(
                        module,
                        LlvmProtectionConfig.selected(13, false, true, false, false, false));

        assertTrue(result.valid());
        assertEquals(List.of("f"), result.affectedFunctions());
        String text = new LlvmTextEmitter().emit(result.module());
        assertTrue(text.contains("xor i32"));
        assertTrue(text.contains("icmp eq i32"));
        assertTrue(text.contains("and i1 %condition"));
        assertTrue(text.contains(", label %left, label %right"));
        assertEquals(
                result.module(),
                new LlvmOpaquePredicatePass()
                        .runDetailed(
                                module,
                                LlvmProtectionConfig.selected(
                                        13, false, true, false, false, false))
                        .module());
    }

    @Test
    void functionsWithoutConditionalBranchesAreStableNoCandidates() {
        LlvmFunction function = new LlvmFunction(
                "linear",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(),
                List.of(new LlvmBasicBlock(
                        "entry",
                        List.of(),
                        new LlvmTerminator(LlvmType.I32, Optional.of("1")))));
        LlvmModule module = new LlvmModule("linear", List.of(function));

        LlvmOpaquePredicateResult result = new LlvmOpaquePredicatePass()
                .runDetailed(
                        module,
                        LlvmProtectionConfig.selected(13, false, true, false, false, false));

        assertSame(module, result.module());
        assertTrue(result.affectedFunctions().isEmpty());
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
                                new LlvmTerminator(LlvmType.I32, Optional.of("1"))),
                        new LlvmBasicBlock(
                                "right",
                                List.of(),
                                new LlvmTerminator(LlvmType.I32, Optional.of("0")))));
        return new LlvmModule("fixture", List.of(function));
    }
}

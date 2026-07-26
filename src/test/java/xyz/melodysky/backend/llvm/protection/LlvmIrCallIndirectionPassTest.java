package xyz.melodysky.backend.llvm.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmIrCallIndirectionRef;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.ir.model.IrCallIndirectionMode;

final class LlvmIrCallIndirectionPassTest {
    @Test
    void rewritesOnlyExplicitlyMarkedCallsThroughHiddenTable() {
        LlvmInstruction protectedCall = LlvmInstruction.raw(
                        Optional.of("%result"),
                        "call i32 @target()")
                .withIrCallIndirection(new LlvmIrCallIndirectionRef(
                        "ircg_001",
                        "irce_001",
                        IrCallIndirectionMode.TABLE));
        LlvmFunction caller = function(
                "caller",
                List.of(protectedCall),
                new LlvmTerminator(LlvmType.I32, Optional.of("%result")));
        LlvmFunction target = function(
                "target",
                List.of(LlvmInstruction.raw(Optional.of("%value"), "add i32 1, 2")),
                new LlvmTerminator(LlvmType.I32, Optional.of("%value")));
        LlvmModule input = new LlvmModule("sample", List.of(caller, target));

        LlvmIrCallIndirectionResult result =
                new LlvmIrCallIndirectionPass().runDetailed(input);

        assertTrue(result.changed());
        assertTrue(result.validationIssues().isEmpty());
        assertEquals(1, result.tableSymbols().size());
        assertTrue(result.module().globals().get(0).definition().contains("ptr @target"));
        String callerText = result.module().functions().stream()
                .filter(function -> function.name().equals("caller"))
                .flatMap(function -> function.blocks().stream())
                .flatMap(block -> block.instructions().stream())
                .flatMap(instruction -> instruction.rawText().stream())
                .reduce((left, right) -> left + "\n" + right)
                .orElseThrow();
        assertTrue(callerText.contains("getelementptr inbounds"));
        assertTrue(callerText.contains("load ptr"));
        assertTrue(callerText.contains("call i32 %j2ll_irci_fn_"));
        assertFalse(callerText.contains("call i32 @target"));
    }

    @Test
    void unmarkedModuleIsIdentityNoOp() {
        LlvmFunction target = function(
                "target",
                List.of(LlvmInstruction.raw(Optional.of("%value"), "add i32 1, 2")),
                new LlvmTerminator(LlvmType.I32, Optional.of("%value")));
        LlvmModule input = new LlvmModule("sample", List.of(target));

        LlvmIrCallIndirectionResult result =
                new LlvmIrCallIndirectionPass().runDetailed(input);

        assertSame(input, result.module());
        assertFalse(result.changed());
    }

    private LlvmFunction function(
            String name,
            List<LlvmInstruction> instructions,
            LlvmTerminator terminator) {
        return new LlvmFunction(
                name,
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(),
                List.of(new LlvmBasicBlock("entry", instructions, terminator)));
    }
}

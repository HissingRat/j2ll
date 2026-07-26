package xyz.melodysky.backend.llvm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LlvmModuleValidatorTest {
    @Test
    void acceptsAValidControlFlowGraph() {
        LlvmFunction function = new LlvmFunction(
                "f",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(),
                List.of(
                        new LlvmBasicBlock(
                                "entry",
                                List.of(new LlvmInstruction(
                                        Optional.of("%c"),
                                        LlvmType.I1,
                                        "icmp eq",
                                        List.of("i32 0", "0"))),
                                LlvmTerminator.branch("%c", "yes", "no")),
                        new LlvmBasicBlock(
                                "yes",
                                List.of(),
                                new LlvmTerminator(LlvmType.I32, Optional.of("1"))),
                        new LlvmBasicBlock(
                                "no",
                                List.of(),
                                new LlvmTerminator(LlvmType.I32, Optional.of("0")))));

        assertTrue(new LlvmModuleValidator()
                .validate(new LlvmModule("m", List.of(function)))
                .isEmpty());
    }

    @Test
    void rejectsDuplicateBlocksAndUnknownTargets() {
        LlvmFunction function = new LlvmFunction(
                "f",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.VOID,
                List.of(),
                List.of(
                        new LlvmBasicBlock("entry", List.of(), LlvmTerminator.gotoBlock("missing")),
                        new LlvmBasicBlock(
                                "entry",
                                List.of(),
                                new LlvmTerminator(LlvmType.VOID, Optional.empty()))));

        List<String> issues =
                new LlvmModuleValidator().validate(new LlvmModule("m", List.of(function)));

        assertEquals(2, issues.size());
        assertTrue(issues.get(0).contains("duplicate block name"));
        assertTrue(issues.get(1).contains("unknown block target"));
    }

    @Test
    void rejectsDuplicateGlobalsAndGlobalFunctionSymbolCollisions() {
        LlvmFunction function = new LlvmFunction(
                "shared",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.VOID,
                List.of(),
                List.of(new LlvmBasicBlock(
                        "entry",
                        List.of(),
                        new LlvmTerminator(LlvmType.VOID, Optional.empty()))));
        LlvmModule module = new LlvmModule(
                "m",
                List.of(),
                List.of(
                        new LlvmGlobal("shared", "internal global i32 0"),
                        new LlvmGlobal("shared", "private global i32 1")),
                List.of(function));

        List<String> issues = new LlvmModuleValidator().validate(module);

        assertEquals(
                List.of(
                        "duplicate global name: shared",
                        "global/function symbol collision: shared"),
                issues);
    }
}

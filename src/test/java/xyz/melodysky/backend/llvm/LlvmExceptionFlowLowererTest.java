package xyz.melodysky.backend.llvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;

class LlvmExceptionFlowLowererTest {
    @Test
    void unprotectedPendingExceptionRunsCleanupAndReturnsWithoutClearing() {
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue result = new IrValue("%result", IrType.I32);
        IrInstruction source = IrInstruction.call(
                        Optional.of(result),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_test")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(),
                        Optional.of(pending)));
        IrBlock block = new IrBlock(
                "entry",
                List.of(source),
                IrTerminator.returnValue(result));
        LlvmInstruction cleanup = LlvmInstruction.raw(
                Optional.empty(),
                "call void @j2ll_test_cleanup()");

        var lowered = new LlvmExceptionFlowLowerer(
                Set.of("entry"),
                xyz.melodysky.testsupport.TestProtectionMaterials
                        .runtimeTokens()).lower(
                block,
                List.of(new LlvmExceptionFlowLowerer.InstructionChunk(
                        source,
                        List.of(LlvmInstruction.raw(
                                Optional.of(result.name()),
                                "call i32 @j2ll_test_throwable()")))),
                new LlvmTerminator(LlvmType.I32, Optional.of("%result")),
                LlvmType.I32,
                List.of(cleanup));

        var unhandled = lowered.blocks().stream()
                .filter(candidate -> candidate.name().startsWith("j2ll.ex.unhandled."))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(cleanup), unhandled.instructions());
        assertEquals(Optional.of("0"), unhandled.terminator().returnValue());
        assertFalse(rawInstructions(lowered).contains("@j2ll_rt_clear_exception"));
        assertFalse(rawInstructions(lowered).contains("@j2ll_rt_rethrow"));
    }

    @Test
    void typedMatcherFailurePreservesItsPendingExceptionAndRunsCleanup() {
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrExceptionEdge handler = new IrExceptionEdge(
                "catch",
                "java/lang/RuntimeException",
                List.of(pending));
        IrInstruction source = IrInstruction.call(
                        Optional.empty(),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_test")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(handler),
                        Optional.of(pending)));
        IrBlock block = new IrBlock(
                "entry",
                List.of(source),
                IrTerminator.returnVoid());
        LlvmInstruction cleanup = LlvmInstruction.raw(
                Optional.empty(),
                "call void @j2ll_test_cleanup()");

        var lowered = new LlvmExceptionFlowLowerer(
                Set.of("entry", "catch"),
                xyz.melodysky.testsupport.TestProtectionMaterials
                        .runtimeTokens()).lower(
                block,
                List.of(new LlvmExceptionFlowLowerer.InstructionChunk(
                        source,
                        List.of(LlvmInstruction.raw(
                                Optional.empty(),
                                "call void @j2ll_test_throwable()")))),
                new LlvmTerminator(LlvmType.VOID, Optional.empty()),
                LlvmType.VOID,
                List.of(cleanup));

        var matcher = lowered.blocks().stream()
                .filter(candidate -> candidate.name().startsWith("j2ll.ex.check."))
                .findFirst()
                .orElseThrow();
        String matcherHelper = xyz.melodysky.testsupport.TestProtectionMaterials.runtimeTokens().helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "instanceof",
                "instanceof:java/lang/RuntimeException");
        assertTrue(matcher.instructions().get(0).rawText().orElseThrow()
                .contains("@" + matcherHelper));
        assertTrue(matcher.instructions().get(1).rawText().orElseThrow()
                .contains("@j2ll_rt_pending_exception"));
        var matcherFailure = lowered.blocks().stream()
                .filter(candidate -> candidate.name().startsWith("j2ll.ex.match.failure."))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(cleanup), matcherFailure.instructions());
        assertFalse(matcherFailure.instructions().stream()
                .map(instruction -> instruction.rawText().orElse(""))
                .anyMatch(text -> text.contains("@j2ll_rt_clear_exception")
                        || text.contains("@j2ll_rt_rethrow")));
    }

    private String rawInstructions(LlvmExceptionFlowLowerer.BlockResult lowered) {
        return lowered.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .map(instruction -> instruction.rawText().orElse(""))
                .reduce("", (left, right) -> left + "\n" + right);
    }
}

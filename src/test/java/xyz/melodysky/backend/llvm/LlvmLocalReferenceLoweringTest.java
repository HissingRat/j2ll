package xyz.melodysky.backend.llvm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlan;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlanner;

class LlvmLocalReferenceLoweringTest {
    private final NativeLocalReferencePlanner planner =
            new NativeLocalReferencePlanner();

    @Test
    void emitsDynamicOwnershipPhiAndReleasesThePreviousLoopHandle() {
        IrValue start = ref("%start");
        IrValue current = ref("%current");
        IrValue next = ref("%next");
        IrMethod method = method(
                List.of(start),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.gotoBlock(
                                        "loop",
                                        List.of(start))),
                        new IrBlock(
                                "loop",
                                List.of(current),
                                List.of(IrInstruction.symbolicConstant(
                                        next,
                                        IrOpcode.CONST_CLASS,
                                        "class:Ljava/lang/String;")),
                                IrTerminator.gotoBlock(
                                        "loop",
                                        List.of(next)))));

        String text = lower(method, planner.plan(method)
                .plan()
                .orElseThrow());

        assertTrue(text.contains(
                "declare void @j2ll_rt_release_local_ref(ptr, ptr, i32)"),
                text);
        assertTrue(text.contains(
                "phi i32 [ 0, %entry ], [ 1, %loop ]"),
                text);
        assertTrue(text.contains(
                "call void @j2ll_rt_release_local_ref("
                        + "ptr %j2ll_env, ptr %current, i32 "
                        + "%j2ll.lref.owned."),
                text);
        assertFalse(text.contains(
                "ptr %j2ll_env, ptr %start, i32"),
                text);
    }

    @Test
    void runsProtectedSiteCleanupAfterClearAndBeforeCatchTransfer() {
        IrValue source = ref("%source");
        IrValue cast = ref("%cast");
        IrValue exception = ref("%exception");
        IrValue caught = ref("%caught");
        IrInstruction checkcast = IrInstruction.operation(
                        Optional.of(cast),
                        IrOpcode.CHECKCAST,
                        List.of(source),
                        "java/lang/String")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.CLASS_CAST,
                        List.of(new IrExceptionEdge(
                                "catch",
                                "java/lang/ClassCastException",
                                List.of(exception))),
                        Optional.of(exception)));
        IrMethod method = method(
                List.of(),
                List.of(
                        new IrBlock(
                                "loop",
                                List.of(
                                        IrInstruction.symbolicConstant(
                                                source,
                                                IrOpcode.CONST_CLASS,
                                                "class:Ljava/lang/String;"),
                                        checkcast),
                                IrTerminator.gotoBlock("loop")),
                        new IrBlock(
                                "catch",
                                List.of(caught),
                                List.of("java/lang/ClassCastException"),
                                List.of(),
                                IrTerminator.gotoBlock("loop"))));

        String text = lower(method, planner.plan(method)
                .plan()
                .orElseThrow());

        int clear = text.indexOf(
                "call void @j2ll_rt_clear_exception(ptr %j2ll_env)");
        int releaseSource = text.indexOf(
                "ptr %j2ll_env, ptr %source, i32 1)",
                clear);
        int catchTransfer = text.indexOf("br label %catch", releaseSource);
        assertTrue(clear >= 0, text);
        assertTrue(releaseSource > clear, text);
        assertTrue(catchTransfer > releaseSource, text);
        assertTrue(text.contains(
                "ptr %j2ll_env, ptr %caught, i32 "
                        + "%j2ll.lref.owned."),
                text);
    }

    @Test
    void insertsAnEdgeCleanupAdapterAndUsesItAsThePhysicalPredecessor() {
        IrValue condition = new IrValue("%condition", IrType.I32);
        IrValue value = ref("%value");
        IrValue kept = ref("%kept");
        IrMethod method = method(
                List.of(condition),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.symbolicConstant(
                                        value,
                                        IrOpcode.CONST_CLASS,
                                        "class:Ljava/lang/String;")),
                                IrTerminator.branch(
                                        condition,
                                        "keep",
                                        List.of(value),
                                        "drop",
                                        List.of())),
                        new IrBlock(
                                "keep",
                                List.of(kept),
                                List.of(),
                                IrTerminator.returnVoid()),
                        new IrBlock(
                                "drop",
                                List.of(),
                                IrTerminator.returnVoid())));

        String text = lower(method, planner.plan(method)
                .plan()
                .orElseThrow());

        assertTrue(text.contains(
                "j2ll.lref.edge."),
                text);
        assertTrue(text.contains(
                "ptr %j2ll_env, ptr %value, i32 1)"),
                text);
        assertTrue(text.contains(
                "%kept = phi ptr [ %value, %entry ]"),
                text);
    }

    private String lower(
            IrMethod method,
            NativeLocalReferencePlan plan) {
        return new LlvmTextEmitter().emit(
                new LlvmModuleLowerer().lowerClass(
                        new IrClass(method.owner(), List.of(method)),
                        LlvmLinkage.EXTERNAL,
                        LlvmVisibility.HIDDEN,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(method.methodKey(), plan)));
    }

    private IrMethod method(
            List<IrValue> parameters,
            List<IrBlock> blocks) {
        return new IrMethod(
                "sample/Refs",
                "run",
                "()V",
                IrType.VOID,
                parameters,
                blocks);
    }

    private IrValue ref(String name) {
        return new IrValue(name, IrType.REFERENCE);
    }
}

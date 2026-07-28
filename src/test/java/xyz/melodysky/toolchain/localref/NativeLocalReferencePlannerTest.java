package xyz.melodysky.toolchain.localref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

class NativeLocalReferencePlannerTest {
    private final NativeLocalReferencePlanner planner =
            new NativeLocalReferencePlanner();

    @Test
    void releasesAnUnusedOwnedReferenceOnEveryLoopIteration() {
        IrValue text = ref("%text");
        IrMethod method = method(
                List.of(),
                List.of(new IrBlock(
                        "loop",
                        List.of(IrInstruction.symbolicConstant(
                                text,
                                IrOpcode.CONST_STRING,
                                "plain:v1:loop")),
                        IrTerminator.gotoBlock("loop"))));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertEquals(
                List.of(text),
                plan.releasesAfter("loop", 0).normalPath());
        assertFalse(plan.releasesAfter(
                "loop",
                0).exceptionalPath().contains(text));
    }

    @Test
    void carriesDynamicOwnershipAcrossABorrowedThenOwnedLoopPhi() {
        IrValue start = ref("%start");
        IrValue current = ref("%current");
        IrValue next = ref("%next");
        IrInstruction getNext = IrInstruction.call(
                Optional.of(next),
                IrOpcode.CALL_VIRTUAL,
                List.of(current),
                "sample/Node#getNext!()Lsample/Node;");
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
                                List.of(getNext),
                                IrTerminator.gotoBlock(
                                        "loop",
                                        List.of(next)))));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertEquals(
                NativeLocalReferenceOwnership.Kind.BORROWED,
                plan.ownershipByValue().get(start.name()).kind());
        assertEquals(
                NativeLocalReferenceOwnership.Kind.DYNAMIC,
                plan.ownershipByValue().get(current.name()).kind());
        assertEquals(
                NativeLocalReferenceOwnership.Kind.OWNED,
                plan.ownershipByValue().get(next.name()).kind());
        assertEquals(
                List.of(current),
                plan.releasesAfter("loop", 0).normalPath());
        assertFalse(plan.instructionReleases().values().stream()
                .flatMap(schedule -> schedule.normalPath().stream())
                .anyMatch(next::equals));
    }

    @Test
    void acceptsALoopCarriedTransferSccWhoseExitReleasesBeforeReproduction() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue source = ref("%source");
        IrValue current = ref("%current");
        IrValue exiting = ref("%exiting");
        IrMethod method = method(
                List.of(condition),
                List.of(
                        new IrBlock(
                                "produce",
                                List.of(IrInstruction.symbolicConstant(
                                        source,
                                        IrOpcode.CONST_CLASS,
                                        "class:Ljava/lang/String;")),
                                IrTerminator.gotoBlock(
                                        "inner",
                                        List.of(source))),
                        new IrBlock(
                                "inner",
                                List.of(current),
                                List.of(),
                                IrTerminator.branch(
                                        condition,
                                        "inner",
                                        List.of(current),
                                        "consume",
                                        List.of(current))),
                        new IrBlock(
                                "consume",
                                List.of(exiting),
                                List.of(IrInstruction.call(
                                        Optional.empty(),
                                        IrOpcode.CALL_VIRTUAL,
                                        List.of(exiting),
                                        "sample/Node#touch!()V")),
                                IrTerminator.gotoBlock("produce"))));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertTrue(plan.releasesAfter(
                        "consume",
                        0)
                .normalPath()
                .contains(exiting));
    }

    @Test
    void releasesDeadLocalsOnTheExceptionalPathAndCaughtThrowableInHandler() {
        IrValue stable = ref("%stable");
        IrValue loopStable = ref("%loopStable");
        IrValue text = ref("%text");
        IrValue exception = ref("%exception");
        IrValue caught = ref("%caught");
        IrValue caughtStable = ref("%caughtStable");
        IrExceptionEdge handler = new IrExceptionEdge(
                "catch",
                "java/lang/Exception",
                List.of(exception, loopStable));
        IrInstruction throwing = IrInstruction.call(
                        Optional.empty(),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(text),
                        "sample_throwing_helper")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(handler),
                        Optional.of(exception)));
        IrMethod method = method(
                List.of(stable),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.gotoBlock(
                                        "loop",
                                        List.of(stable))),
                        new IrBlock(
                                "loop",
                                List.of(loopStable),
                                List.of(
                                        IrInstruction.symbolicConstant(
                                                text,
                                                IrOpcode.CONST_STRING,
                                                "plain:v1:catch"),
                                        throwing),
                                IrTerminator.gotoBlock(
                                        "loop",
                                        List.of(loopStable))),
                        new IrBlock(
                                "catch",
                                List.of(caught, caughtStable),
                                List.of("java/lang/Exception"),
                                List.of(),
                                IrTerminator.gotoBlock(
                                        "loop",
                                        List.of(caughtStable)))));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertTrue(plan.releasesAfter(
                "loop",
                1).exceptionalPath().contains(text));
        assertTrue(plan.releasesBeforeTerminator(
                "catch").contains(caught));
        assertEquals(
                NativeLocalReferenceOwnership.Kind.OWNED,
                plan.ownershipByValue().get(exception.name()).kind());
    }

    @Test
    void transfersAnOwnedCheckcastOnNormalPathButReleasesItOnCatchPath() {
        IrValue source = ref("%source");
        IrValue cast = ref("%cast");
        IrValue exception = ref("%exception");
        IrValue caught = ref("%caught");
        IrInstruction checkcast = IrInstruction.unary(
                        cast,
                        IrOpcode.CHECKCAST,
                        source)
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
                                                IrOpcode.CONST_STRING,
                                                "plain:v1:cast"),
                                        checkcast),
                                IrTerminator.gotoBlock("loop")),
                        new IrBlock(
                                "catch",
                                List.of(caught),
                                List.of(
                                        "java/lang/ClassCastException"),
                                List.of(),
                                IrTerminator.gotoBlock("loop"))));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertFalse(plan.releasesAfter(
                "loop",
                1).normalPath().contains(source));
        assertTrue(plan.releasesAfter(
                "loop",
                1).exceptionalPath().contains(source));
        assertTrue(plan.releasesAfter(
                "loop",
                1).normalPath().contains(cast));
    }

    @Test
    void doesNotReleaseAFuturePendingExceptionOnAnEarlierHandlerPath() {
        IrValue firstResult = new IrValue("%firstResult", IrType.I32);
        IrValue secondResult = new IrValue("%secondResult", IrType.I32);
        IrValue firstException = ref("%firstException");
        IrValue secondException = ref("%secondException");
        IrValue caught = ref("%caught");
        IrInstruction first = pendingIntCall(
                firstResult,
                firstException,
                "catch");
        IrInstruction second = pendingIntCall(
                secondResult,
                secondException,
                "catch");
        IrMethod method = method(
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(first, second),
                                IrTerminator.returnVoid()),
                        new IrBlock(
                                "catch",
                                List.of(caught),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.returnVoid())));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertFalse(plan.releasesAfter(
                        "entry",
                        0)
                .exceptionalPath()
                .contains(secondException));
    }

    @Test
    void rejectsOneOwnedHandleTransferredToTwoParametersOnOneEdge() {
        IrValue owned = ref("%owned");
        IrValue left = ref("%left");
        IrValue right = ref("%right");
        IrMethod method = method(
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.symbolicConstant(
                                        owned,
                                        IrOpcode.CONST_STRING,
                                        "plain:v1:duplicate")),
                                IrTerminator.gotoBlock(
                                        "merge",
                                        List.of(owned, owned))),
                        new IrBlock(
                                "merge",
                                List.of(left, right),
                                List.of(),
                                IrTerminator.returnVoid())));

        NativeLocalReferencePlanningResult result = planner.plan(method);

        assertTrue(result.plan().isEmpty());
        assertTrue(result.failureReason()
                .orElseThrow()
                .contains(
                        "transferred to multiple reference parameters"));
    }

    @Test
    void rejectsAnOwnedEdgeArgumentDuplicatedAsADirectLiveIn() {
        IrValue owned = ref("%owned");
        IrValue parameter = ref("%parameter");
        IrInstruction useDirect = IrInstruction.operation(
                Optional.empty(),
                IrOpcode.CALL_RUNTIME_HELPER,
                List.of(owned),
                "j2ll_rt_consume");
        IrMethod method = method(
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(IrInstruction.symbolicConstant(
                                        owned,
                                        IrOpcode.CONST_STRING,
                                        "plain:v1:direct-live-in")),
                                IrTerminator.gotoBlock(
                                        "merge",
                                        List.of(owned))),
                        new IrBlock(
                                "merge",
                                List.of(parameter),
                                List.of(useDirect),
                                IrTerminator.returnVoid())));

        NativeLocalReferencePlanningResult result = planner.plan(method);

        assertTrue(result.plan().isEmpty());
        assertTrue(result.failureReason()
                .orElseThrow()
                .contains("direct successor live-in"));
    }

    @Test
    void rejectsProtectedHandlersWithDifferentReferenceLiveSets() {
        IrValue source = ref("%source");
        IrValue exception = ref("%exception");
        IrValue firstCaught = ref("%firstCaught");
        IrValue secondCaught = ref("%secondCaught");
        IrValue carried = ref("%carried");
        IrInstruction checkcast = IrInstruction.unary(
                        ref("%cast"),
                        IrOpcode.CHECKCAST,
                        source)
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.CLASS_CAST,
                        List.of(
                                new IrExceptionEdge(
                                        "first",
                                        "java/lang/ClassCastException",
                                        List.of(exception)),
                                new IrExceptionEdge(
                                        "second",
                                        "java/lang/RuntimeException",
                                        List.of(exception, source))),
                        Optional.of(exception)));
        IrMethod method = method(
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(
                                        IrInstruction.symbolicConstant(
                                                source,
                                                IrOpcode.CONST_STRING,
                                                "plain:v1:handlers"),
                                        checkcast),
                                IrTerminator.returnVoid()),
                        new IrBlock(
                                "first",
                                List.of(firstCaught),
                                List.of("java/lang/ClassCastException"),
                                List.of(),
                                IrTerminator.returnVoid()),
                        new IrBlock(
                                "second",
                                List.of(secondCaught, carried),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.returnVoid())));

        NativeLocalReferencePlanningResult result = planner.plan(method);

        assertTrue(result.plan().isEmpty());
        assertTrue(result.failureReason()
                .orElseThrow()
                .contains("different reference live sets"));
    }

    @Test
    void carriesOwnedStateAcrossAnExplicitThrowHandlerEdge() {
        IrValue throwable = ref("%throwable");
        IrValue owned = ref("%owned");
        IrValue caught = ref("%caught");
        IrValue carried = ref("%carried");
        IrMethod method = method(
                List.of(throwable),
                List.of(
                        new IrBlock(
                                "loop",
                                List.of(),
                                List.of(),
                                List.of(new IrExceptionEdge(
                                        "catch",
                                        "java/lang/RuntimeException",
                                        List.of(throwable, owned))),
                                List.of(IrInstruction.symbolicConstant(
                                        owned,
                                        IrOpcode.CONST_STRING,
                                        "plain:v1:athrow")),
                                IrTerminator.throwValue(throwable)),
                        new IrBlock(
                                "catch",
                                List.of(caught, carried),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.gotoBlock("loop"))));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertFalse(plan.releasesAfter(
                        "loop",
                        0)
                .normalPath()
                .contains(owned));
        assertTrue(plan.releasesBeforeTerminator(
                        "catch")
                .contains(carried));
    }

    @Test
    void retainsOwnedReferenceNeededOnlyByANestedProtectedHandler() {
        IrValue owned = ref("%owned");
        IrValue outerResult = new IrValue("%outerResult", IrType.I32);
        IrValue outerException = ref("%outerException");
        IrValue outerCaught = ref("%outerCaught");
        IrValue innerResult = new IrValue("%innerResult", IrType.I32);
        IrValue innerException = ref("%innerException");
        IrValue innerCaught = ref("%innerCaught");
        IrMethod method = method(
                List.of(),
                List.of(
                        new IrBlock(
                                "outer",
                                List.of(
                                        IrInstruction.symbolicConstant(
                                                owned,
                                                IrOpcode.CONST_STRING,
                                                "plain:v1:nested-handler"),
                                        pendingIntCall(
                                                outerResult,
                                                outerException,
                                                "middle")),
                                IrTerminator.gotoBlock("outer")),
                        new IrBlock(
                                "middle",
                                List.of(outerCaught),
                                List.of("java/lang/RuntimeException"),
                                List.of(pendingIntCall(
                                        innerResult,
                                        innerException,
                                        "inner")),
                                IrTerminator.gotoBlock("outer")),
                        new IrBlock(
                                "inner",
                                List.of(innerCaught),
                                List.of("java/lang/RuntimeException"),
                                List.of(IrInstruction.operation(
                                        Optional.empty(),
                                        IrOpcode.CALL_RUNTIME_HELPER,
                                        List.of(owned),
                                        "j2ll_rt_consume")),
                                IrTerminator.gotoBlock("outer"))));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertFalse(
                plan.releasesAfter("outer", 0)
                        .normalPath()
                        .contains(owned),
                "the outer protected site must retain the handle for "
                        + "the nested exceptional-only direct live-in");
        assertTrue(
                plan.releasesAfter("outer", 1)
                        .normalPath()
                        .contains(owned),
                "normal completion of the outer protected site must "
                        + "release the exceptional-only handle");
    }

    @Test
    void releasesUnusedOwnedPhiInputOnACaughtExceptionalBackedge() {
        IrValue produced = ref("%produced");
        IrValue carried = ref("%carried");
        IrValue result = new IrValue("%result", IrType.I32);
        IrValue exception = ref("%exception");
        IrValue caught = ref("%caught");
        IrMethod method = method(
                List.of(),
                List.of(
                        new IrBlock(
                                "produce",
                                List.of(IrInstruction.symbolicConstant(
                                        produced,
                                        IrOpcode.CONST_STRING,
                                        "plain:v1:unused-phi")),
                                IrTerminator.gotoBlock(
                                        "loop",
                                        List.of(produced))),
                        new IrBlock(
                                "loop",
                                List.of(carried),
                                List.of(pendingIntCall(
                                        result,
                                        exception,
                                        "catch")),
                                IrTerminator.gotoBlock("produce")),
                        new IrBlock(
                                "catch",
                                List.of(caught),
                                List.of("java/lang/RuntimeException"),
                                List.of(),
                                IrTerminator.gotoBlock("produce"))));

        NativeLocalReferencePlan plan =
                planner.plan(method).plan().orElseThrow();

        assertTrue(
                plan.releasesAfter("loop", 0)
                        .exceptionalPath()
                        .contains(carried),
                "the caught path bypasses the normal terminator cleanup");
    }

    private IrInstruction pendingIntCall(
            IrValue result,
            IrValue exception,
            String handler) {
        return IrInstruction.operation(
                        Optional.of(result),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_test")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(new IrExceptionEdge(
                                handler,
                                "java/lang/RuntimeException",
                                List.of(exception))),
                        Optional.of(exception)));
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

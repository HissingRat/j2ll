package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;

class NativeLocalReferenceCallGraphSafetyTest {
    private static final String OWNER = "pkg/CallGraphRefs";

    private final NativeLocalReferenceCallGraphSafety safety =
            new NativeLocalReferenceCallGraphSafety();

    @Test
    void rejectsLoopCallingVoidCalleeThatCreatesOwnedReference() {
        IrMethod leaf = referenceProducingLeaf("leaf");
        IrMethod caller = loopingCaller("caller", leaf.methodKey());

        NativeLocalReferenceCallGraphAnalysis analysis = safety.analyze(
                Map.of(
                        caller.methodKey(), caller,
                        leaf.methodKey(), leaf),
                Set.of(caller.methodKey(), leaf.methodKey()));

        assertEquals(
                Set.of(caller.methodKey(), leaf.methodKey()),
                analysis.referenceProducingMethodKeys());
        assertEquals(
                Set.of(caller.methodKey()),
                analysis.unboundedMethodKeys());
    }

    @Test
    void propagatesReferenceProductionThroughTransitiveVoidCalls() {
        IrMethod leaf = referenceProducingLeaf("leaf");
        IrMethod middle = acyclicCaller("middle", leaf.methodKey());
        IrMethod caller = loopingCaller("caller", middle.methodKey());

        NativeLocalReferenceCallGraphAnalysis analysis = safety.analyze(
                Map.of(
                        caller.methodKey(), caller,
                        middle.methodKey(), middle,
                        leaf.methodKey(), leaf),
                Set.of(
                        caller.methodKey(),
                        middle.methodKey(),
                        leaf.methodKey()));

        assertEquals(
                Set.of(
                        caller.methodKey(),
                        middle.methodKey(),
                        leaf.methodKey()),
                analysis.referenceProducingMethodKeys());
        assertEquals(
                Set.of(caller.methodKey()),
                analysis.unboundedMethodKeys());
    }

    @Test
    void rejectsEveryMemberOfReferenceProducingDirectCallScc() {
        IrMethod first = acyclicCaller(
                "first",
                methodKey("second"));
        IrMethod second = method(
                "second",
                List.of(
                        stringConstant("%text", "plain:v1:scc"),
                        directVoidCall(first.methodKey())),
                IrTerminator.returnVoid());

        NativeLocalReferenceCallGraphAnalysis analysis = safety.analyze(
                Map.of(
                        first.methodKey(), first,
                        second.methodKey(), second),
                Set.of(first.methodKey(), second.methodKey()));

        assertEquals(
                Set.of(first.methodKey(), second.methodKey()),
                analysis.referenceProducingMethodKeys());
        assertEquals(
                Set.of(first.methodKey(), second.methodKey()),
                analysis.unboundedMethodKeys());
    }

    @Test
    void ignoresCallsThatAreNotInTheFrozenDirectCallClosure() {
        IrMethod leaf = referenceProducingLeaf("leaf");
        IrMethod caller = loopingCaller("caller", leaf.methodKey());

        NativeLocalReferenceCallGraphAnalysis analysis = safety.analyze(
                Map.of(
                        caller.methodKey(), caller,
                        leaf.methodKey(), leaf),
                Set.of(caller.methodKey()));

        assertEquals(Set.of(), analysis.referenceProducingMethodKeys());
        assertEquals(Set.of(), analysis.unboundedMethodKeys());
    }

    private IrMethod referenceProducingLeaf(String name) {
        return method(
                name,
                List.of(stringConstant(
                        "%text",
                        "plain:v1:" + name)),
                IrTerminator.returnVoid());
    }

    private IrMethod acyclicCaller(String name, String target) {
        return method(
                name,
                List.of(directVoidCall(target)),
                IrTerminator.returnVoid());
    }

    private IrMethod loopingCaller(String name, String target) {
        return new IrMethod(
                OWNER,
                name,
                "()V",
                IrType.VOID,
                List.of(),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.gotoBlock("loop")),
                        new IrBlock(
                                "loop",
                                List.of(directVoidCall(target)),
                                IrTerminator.gotoBlock("loop"))));
    }

    private IrMethod method(
            String name,
            List<IrInstruction> instructions,
            IrTerminator terminator) {
        return new IrMethod(
                OWNER,
                name,
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        instructions,
                        terminator)));
    }

    private IrInstruction stringConstant(
            String valueName,
            String carrier) {
        return IrInstruction.symbolicConstant(
                new IrValue(valueName, IrType.REFERENCE),
                IrOpcode.CONST_STRING,
                carrier);
    }

    private IrInstruction directVoidCall(String target) {
        return IrInstruction.call(
                Optional.empty(),
                IrOpcode.CALL_STATIC,
                List.of(),
                target);
    }

    private String methodKey(String name) {
        return OWNER + "#" + name + "!()V";
    }
}

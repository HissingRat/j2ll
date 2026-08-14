package xyz.melodysky.toolchain.localref;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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

class NativeLocalReferencePlanValidatorTest {
    private final NativeLocalReferencePlanValidator validator =
            new NativeLocalReferencePlanValidator();

    @Test
    void rejectsReplacementCycleWhenPendingReferenceIsTransferredTwice() {
        Fixture fixture = replacementFixture(true);

        Optional<String> failure = validator.validate(
                fixture.method(),
                fixture.plan());

        assertTrue(failure.orElseThrow()
                .contains("transferred to multiple reference parameters"));
    }

    private Fixture replacementFixture(
            boolean duplicatePending) {
        IrValue initial = ref("%initial");
        IrValue caught = ref("%caught");
        IrValue carried = ref("%carried");
        IrValue pending = ref("%pending");
        IrValue recovered = ref("%recovered");
        List<IrValue> retryArguments = duplicatePending
                ? List.of(pending, pending)
                : List.of(pending, carried);
        IrInstruction retry = IrInstruction.operation(
                        Optional.empty(),
                        IrOpcode.MONITOR_EXIT_ON_EXCEPTION,
                        List.of(),
                        "monitorExit")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(new IrExceptionEdge(
                                "cleanup",
                                "<any>",
                                retryArguments)),
                        Optional.of(pending)));
        IrMethod method = new IrMethod(
                "sample/Refs",
                "run",
                "(Ljava/lang/Object;)V",
                IrType.VOID,
                List.of(initial),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.gotoBlock(
                                        "cleanup",
                                        List.of(initial, initial))),
                        new IrBlock(
                                "cleanup",
                                List.of(caught, carried),
                                List.of("<any>"),
                                List.of(new IrExceptionEdge(
                                        "recovered",
                                        "java/lang/Throwable",
                                        List.of(caught))),
                                List.of(retry),
                                IrTerminator.throwValue(caught)),
                        new IrBlock(
                                "recovered",
                                List.of(recovered),
                                List.of("java/lang/Throwable"),
                                List.of(),
                                IrTerminator.returnVoid())));
        NativeLocalReferenceInstructionSite site =
                new NativeLocalReferenceInstructionSite("cleanup", 0);
        NativeLocalReferencePlan plan = new NativeLocalReferencePlan(
                method.methodKey(),
                Map.of(
                        initial.name(), NativeLocalReferenceOwnership.borrowed(),
                        caught.name(), NativeLocalReferenceOwnership.dynamic(),
                        carried.name(), NativeLocalReferenceOwnership.dynamic(),
                        pending.name(), NativeLocalReferenceOwnership.owned(),
                        recovered.name(), NativeLocalReferenceOwnership.dynamic()),
                Map.of(site, new NativeLocalReferenceReleaseSchedule(
                        List.of(),
                        List.of(caught))),
                Map.of(),
                Map.of());
        return new Fixture(method, plan);
    }

    private IrValue ref(String name) {
        return new IrValue(name, IrType.REFERENCE);
    }

    private record Fixture(
            IrMethod method,
            NativeLocalReferencePlan plan) {
    }
}

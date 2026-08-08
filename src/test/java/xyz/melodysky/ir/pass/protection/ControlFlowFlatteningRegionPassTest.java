package xyz.melodysky.ir.pass.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrExceptionEdge;
import xyz.melodysky.ir.model.IrExceptionSite;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlanner;

class ControlFlowFlatteningRegionPassTest {
    @Test
    void rewritesSafeRegionAndPreservesProtectedBoundaryVerbatim() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue helperResult = new IrValue("%helper", IrType.I32);
        IrValue pending = new IrValue("%pending", IrType.REFERENCE);
        IrValue caught = new IrValue("%caught", IrType.REFERENCE);
        IrValue failure = new IrValue("%failure", IrType.I32);
        IrValue yes = new IrValue("%yes", IrType.I32);
        IrValue no = new IrValue("%no", IrType.I32);
        IrExceptionEdge handler = new IrExceptionEdge(
                "handler",
                "java/lang/RuntimeException",
                List.of(pending));
        IrInstruction protectedCall = IrInstruction.call(
                        java.util.Optional.of(helperResult),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_int_helper")
                .withExceptionSite(new IrExceptionSite(
                        IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                        List.of(handler),
                        java.util.Optional.of(pending)));
        IrBlock protectedBoundary = new IrBlock(
                "protected_boundary",
                List.of(protectedCall),
                IrTerminator.gotoBlock("safe_header"));
        IrBlock handlerBlock = new IrBlock(
                "handler",
                List.of(caught),
                List.of("java/lang/RuntimeException"),
                List.of(IrInstruction.constInt(failure, -1)),
                IrTerminator.returnValue(failure));
        IrMethod method = new IrMethod(
                "pkg/Partial",
                "choose",
                "(Z)I",
                IrType.I32,
                List.of(condition),
                List.of(
                        protectedBoundary,
                        new IrBlock(
                                "safe_header",
                                List.of(),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock(
                                "yes",
                                List.of(IrInstruction.constInt(yes, 7)),
                                IrTerminator.returnValue(yes)),
                        new IrBlock(
                                "no",
                                List.of(IrInstruction.constInt(no, 3)),
                                IrTerminator.returnValue(no)),
                        handlerBlock));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(101));

        assertTrue(result.reports().stream().anyMatch(report ->
                report.passName().equals("CONTROL_FLOW_FLATTENING")
                        && report.status().equals("RAN")
                        && report.affectedMethods().equals(List.of(method.methodKey()))));
        assertSame(protectedBoundary, result.method().blocks().get(0));
        assertTrue(result.method().blocks().stream().anyMatch(block -> block == handlerBlock));
        assertEquals(List.of(protectedCall), result.method().blocks().get(0).instructions());
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.SWITCH));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());

        List<String> generatedNames = result.method().blocks().stream()
                .map(IrBlock::name)
                .filter(name -> name.startsWith("cff_"))
                .toList();
        assertFalse(generatedNames.isEmpty());
        assertTrue(generatedNames.stream().noneMatch(name ->
                name.contains("safe_header") || name.contains("yes") || name.contains("no")));
    }

    @Test
    void ownedReferenceCreatedBeforeRegionKeepsABoundedNativeLifetimePlan() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue owned = new IrValue("%owned", IrType.REFERENCE);
        IrBlock ownedBoundary = new IrBlock(
                "owned_boundary",
                List.of(IrInstruction.operation(
                        java.util.Optional.of(owned),
                        IrOpcode.CONST_STRING,
                        List.of(),
                        "string:value")),
                IrTerminator.gotoBlock("safe_header"));
        IrMethod method = new IrMethod(
                "pkg/OwnedBeforeRegion",
                "choose",
                "(Z)Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(condition),
                List.of(
                        ownedBoundary,
                        new IrBlock(
                                "safe_header",
                                List.of(),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock("yes", List.of(), IrTerminator.returnValue(owned)),
                        new IrBlock("no", List.of(), IrTerminator.returnValue(owned))));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());

        IrMethod rewritten = new ControlFlowFlatteningPass().run(
                method,
                ProtectionConfig.enabled(103));

        assertSame(ownedBoundary, rewritten.blocks().get(0));
        assertTrue(rewritten.blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.SWITCH));
        assertTrue(new IrMethodValidator().validate(rewritten).isEmpty());
        var localReferences = new NativeLocalReferencePlanner().plan(rewritten);
        assertTrue(localReferences.plan().isPresent(), () ->
                localReferences.failureReason().orElse("missing local-reference plan"));
    }

    @Test
    void monitorBoundaryStaysOutsideAFlattenedRegion() {
        IrValue monitor = new IrValue("%monitor", IrType.REFERENCE);
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue yes = new IrValue("%yes", IrType.I32);
        IrValue no = new IrValue("%no", IrType.I32);
        IrBlock monitorBoundary = new IrBlock(
                "entry",
                List.of(IrInstruction.operation(
                        java.util.Optional.empty(),
                        IrOpcode.MONITOR_ENTER,
                        List.of(monitor),
                        "monitor")),
                IrTerminator.gotoBlock("safe_header"));
        IrMethod method = new IrMethod(
                "pkg/MonitorRegion",
                "choose",
                "(Ljava/lang/Object;Z)I",
                IrType.I32,
                List.of(monitor, condition),
                List.of(
                        monitorBoundary,
                        new IrBlock(
                                "safe_header",
                                List.of(),
                                IrTerminator.branch(condition, "yes", "no")),
                        new IrBlock(
                                "yes",
                                List.of(IrInstruction.constInt(yes, 1)),
                                IrTerminator.returnValue(yes)),
                        new IrBlock(
                                "no",
                                List.of(IrInstruction.constInt(no, 0)),
                                IrTerminator.returnValue(no))));

        var result = new ProtectionPipeline(List.of(new ControlFlowFlatteningPass()))
                .runDetailed(method, ProtectionConfig.enabled(107));

        assertTrue(result.reports().stream().anyMatch(report ->
                report.passName().equals("CONTROL_FLOW_FLATTENING")
                        && report.status().equals("RAN")));
        assertSame(monitorBoundary, result.method().blocks().get(0));
        assertTrue(result.method().blocks().stream()
                .anyMatch(block -> block.terminator().kind() == IrTerminatorKind.SWITCH));
        assertTrue(new IrMethodValidator().validate(result.method()).isEmpty());
    }

    @Test
    void sharesBranchTransitionsByInternalTarget() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue merged = new IrValue("%merged", IrType.I32);
        IrValue exited = new IrValue("%exited", IrType.I32);
        IrMethod method = new IrMethod(
                "pkg/SharedTransition",
                "choose",
                "(Z)I",
                IrType.I32,
                List.of(condition),
                List.of(
                        new IrBlock(
                                "entry",
                                List.of(),
                                IrTerminator.branch(condition, "left", "right")),
                        new IrBlock(
                                "left",
                                List.of(),
                                IrTerminator.branch(condition, "merge", "exit")),
                        new IrBlock(
                                "right",
                                List.of(),
                                IrTerminator.branch(condition, "merge", "exit")),
                        new IrBlock(
                                "merge",
                                List.of(IrInstruction.constInt(merged, 7)),
                                IrTerminator.returnValue(merged)),
                        new IrBlock(
                                "exit",
                                List.of(IrInstruction.constInt(exited, 3)),
                                IrTerminator.returnValue(exited))));

        IrMethod rewritten = new ControlFlowFlatteningPass().run(
                method,
                ProtectionConfig.enabled(109));

        long transitions = rewritten.blocks().stream()
                .map(IrBlock::name)
                .filter(name -> name.startsWith("cff_t_"))
                .count();
        assertEquals(4, transitions);
        assertEquals(
                method.blocks().size() + 2 + transitions,
                rewritten.blocks().size(),
                "one region may add only an entry shim, dispatcher, and one shared transition per target");
        assertTrue(new IrMethodValidator().validate(rewritten).isEmpty());
    }

    @Test
    void parallelBranchEdgesShareOneTransitionWithoutExtendingOwnedLifetime() {
        IrValue condition = new IrValue("%condition", IrType.I1);
        IrValue owned = new IrValue("%owned", IrType.REFERENCE);
        IrBlock ownedBoundary = new IrBlock(
                "entry",
                List.of(IrInstruction.operation(
                        java.util.Optional.of(owned),
                        IrOpcode.CONST_STRING,
                        List.of(),
                        "string:value")),
                IrTerminator.gotoBlock("branch"));
        IrMethod method = new IrMethod(
                "pkg/ParallelTransition",
                "choose",
                "(Z)Ljava/lang/String;",
                IrType.REFERENCE,
                List.of(condition),
                List.of(
                        ownedBoundary,
                        new IrBlock(
                                "branch",
                                List.of(),
                                IrTerminator.branch(condition, "merge", "merge")),
                        new IrBlock(
                                "merge",
                                List.of(),
                                IrTerminator.returnValue(owned))));

        IrMethod rewritten = new ControlFlowFlatteningPass().run(
                method,
                ProtectionConfig.enabled(113));

        assertSame(ownedBoundary, rewritten.blocks().get(0));
        assertEquals(1, rewritten.blocks().stream()
                .map(IrBlock::name)
                .filter(name -> name.startsWith("cff_t_"))
                .count());
        assertTrue(new IrMethodValidator().validate(rewritten).isEmpty());
        var localReferences = new NativeLocalReferencePlanner().plan(rewritten);
        assertTrue(localReferences.plan().isPresent(), () ->
                localReferences.failureReason().orElse("missing local-reference plan"));
    }
}

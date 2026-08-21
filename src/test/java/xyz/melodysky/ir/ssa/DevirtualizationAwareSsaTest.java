package xyz.melodysky.ir.ssa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.callgraph.CallSiteIds;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.callgraph.DevirtualizationDecision;
import xyz.melodysky.analysis.callgraph.DevirtualizationPlan;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.testsupport.TestProtectionMaterials;

class DevirtualizationAwareSsaTest implements Opcodes {
    private static final String CALLER = "pkg/Caller";
    private static final String RECEIVER = "pkg/Receiver";
    private static final MethodSignature CALL_SIGNATURE =
            new MethodSignature("callTwice", "(Lpkg/Receiver;)V");
    private static final MethodSignature TARGET_SIGNATURE =
            new MethodSignature("run", "()V");

    @Test
    void consumesDecisionsByExactBytecodeSiteWithoutCollapsingDuplicateCalls() {
        ParsedMethod method = parsedCaller();
        CallTarget target = CallTarget.known(RECEIVER, TARGET_SIGNATURE);
        DevirtualizationPlan plan = new DevirtualizationPlan(List.of(
                directDecision(callSite(1), target),
                dispatchDecision(callSite(3), target)));

        var result = new BytecodeToSsaLowerer(
                        TestProtectionMaterials.runtimeTokens(),
                        plan)
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow());

        assertEquals(LoweringStatus.NATIVE_LOWERED, result.artifact().orElseThrow().status());
        List<IrInstruction> calls = result.artifact().orElseThrow().irMethod().orElseThrow()
                .blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_DIRECT
                        || instruction.opcode() == IrOpcode.CALL_VIRTUAL)
                .toList();
        assertEquals(List.of(IrOpcode.CALL_DIRECT, IrOpcode.CALL_VIRTUAL),
                calls.stream().map(IrInstruction::opcode).toList());
        assertEquals(RECEIVER + "#" + TARGET_SIGNATURE, calls.get(0).symbol().orElseThrow());
        assertEquals(1, calls.get(0).operands().size(), "direct call must retain the receiver");
        assertEquals(RECEIVER + "#" + TARGET_SIGNATURE, calls.get(1).symbol().orElseThrow());
        List<IrInstruction> instructions = result.artifact().orElseThrow().irMethod().orElseThrow()
                .blocks().get(0).instructions();
        int directIndex = instructions.indexOf(calls.get(0));
        IrInstruction nullGuard = instructions.get(directIndex - 1);
        assertEquals(IrOpcode.CALL_RUNTIME_HELPER, nullGuard.opcode());
        assertEquals("j2ll_rt_objects_require_non_null", nullGuard.symbol().orElseThrow());
        assertEquals(nullGuard.result().orElseThrow(), calls.get(0).operands().get(0));
    }

    @Test
    void failsClosedWhenTheAuthoritativePlanMissesAnExactCallSite() {
        ParsedMethod method = parsedCaller();
        CallTarget target = CallTarget.known(RECEIVER, TARGET_SIGNATURE);
        DevirtualizationPlan incomplete = new DevirtualizationPlan(List.of(
                directDecision(callSite(1), target)));

        var result = new BytecodeToSsaLowerer(
                        TestProtectionMaterials.runtimeTokens(),
                        incomplete)
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow());

        assertTrue(result.artifact().isEmpty());
        assertEquals(LoweringDiagnostics.CALL_ANALYSIS_PLAN_MISMATCH,
                result.diagnostics().get(0).code());
        assertTrue(result.diagnostics().get(0).message().contains(callSite(3)));
    }

    private DevirtualizationDecision directDecision(String site, CallTarget target) {
        return new DevirtualizationDecision(
                site,
                InvokeKind.VIRTUAL,
                List.of(target),
                Optional.of(target),
                false,
                "single target",
                false);
    }

    private DevirtualizationDecision dispatchDecision(String site, CallTarget target) {
        return new DevirtualizationDecision(
                site,
                InvokeKind.VIRTUAL,
                List.of(target),
                Optional.empty(),
                true,
                "direct target unavailable",
                true);
    }

    private String callSite(int instructionIndex) {
        return CallSiteIds.forInstruction(CALLER, CALL_SIGNATURE, instructionIndex);
    }

    private ParsedMethod parsedCaller() {
        return new AsmClassParser()
                .parse(new ClassFileEntry("pkg/Caller.class", callerBytes(), "fixture"))
                .artifact()
                .orElseThrow()
                .methods().stream()
                .filter(method -> method.name().equals(CALL_SIGNATURE.name()))
                .findFirst()
                .orElseThrow();
    }

    private byte[] callerBytes() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, CALLER, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                CALL_SIGNATURE.name(),
                CALL_SIGNATURE.descriptor(),
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, RECEIVER, TARGET_SIGNATURE.name(), TARGET_SIGNATURE.descriptor(), false);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(INVOKEVIRTUAL, RECEIVER, TARGET_SIGNATURE.name(), TARGET_SIGNATURE.descriptor(), false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

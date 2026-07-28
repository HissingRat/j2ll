package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.callgraph.CallSite;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.ir.pass.protection.IrCallIndirectionReasons;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.protection.audit.ProtectionApplicability;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;

class ProgramIrProtectionCoordinatorTest implements Opcodes {
    @Test
    void callIndirectionUsesNativePlanAbiWhenPartitioningEqualIrSignatures() {
        IrMethod plainTarget = constantMethod("plainTarget", 7);
        IrMethod envTarget = constantMethod("envTarget", 9);
        IrValue plainResult = new IrValue("%plain_result", IrType.I32);
        IrValue envResult = new IrValue("%env_result", IrType.I32);
        IrMethod caller = new IrMethod(
                "pkg/Calls",
                "caller",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(
                                IrInstruction.call(
                                        Optional.of(plainResult),
                                        IrOpcode.CALL_STATIC,
                                        List.of(),
                                        plainTarget.methodKey()),
                                IrInstruction.call(
                                        Optional.of(envResult),
                                        IrOpcode.CALL_STATIC,
                                        List.of(),
                                        envTarget.methodKey())),
                        IrTerminator.returnValue(envResult))));
        NativeImplementationPlan implementationPlan = new NativeImplementationPlan(List.of(
                implementation(caller, ACC_PUBLIC | ACC_STATIC, false, false),
                implementation(plainTarget, ACC_PUBLIC | ACC_STATIC, false, false),
                implementation(envTarget, ACC_PUBLIC | ACC_STATIC, true, false)));

        ProgramIrProtectionResult result = new ProgramIrProtectionCoordinator().run(
                Map.of(
                        caller.methodKey(), caller,
                        plainTarget.methodKey(), plainTarget,
                        envTarget.methodKey(), envTarget),
                implementationPlan,
                new ParsedProgram(List.of()),
                new ReflectionPlan(List.of(), List.of(), List.of(), List.of()),
                new CallGraph(List.of()),
                callIndirectionOnly(),
                29L);

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(
                2,
                result.javaMethods().get(caller.methodKey()).blocks().get(0)
                        .instructions().stream()
                        .map(instruction -> instruction.callIndirection()
                                .orElseThrow()
                                .groupId())
                        .distinct()
                        .count());
    }

    @Test
    void invokedynamicResolutionInTheSameCallerDoesNotEnterDirectCallClassification() {
        IrValue receiver = new IrValue("%receiver", IrType.REFERENCE);
        IrValue callResult = new IrValue("%call_result", IrType.I32);
        IrMethod caller = new IrMethod(
                "pkg/Caller",
                "call",
                "(Lpkg/Target;)I",
                IrType.I32,
                List.of(receiver),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.call(
                                Optional.of(callResult),
                                IrOpcode.CALL_VIRTUAL,
                                List.of(receiver),
                                "pkg/Target#value!()I")),
                        IrTerminator.returnValue(callResult))));
        IrValue targetSelf = new IrValue("%self", IrType.REFERENCE);
        IrValue targetResult = new IrValue("%target_result", IrType.I32);
        IrMethod target = new IrMethod(
                "pkg/Target",
                "value",
                "()I",
                IrType.I32,
                List.of(targetSelf),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(targetResult, 7)),
                        IrTerminator.returnValue(targetResult))));

        MethodSignature callerSignature =
                new MethodSignature(caller.name(), caller.descriptor());
        MethodSignature targetSignature =
                new MethodSignature(target.name(), target.descriptor());
        CallResolution dynamicResolution = new CallResolution(
                new CallSite(
                        "dynamic",
                        caller.owner(),
                        callerSignature,
                        0,
                        InvokeKind.DYNAMIC,
                        "java/lang/invoke/StringConcatFactory",
                        new MethodSignature("makeConcat", "()Ljava/lang/String;")),
                List.of(CallTarget.unknownExternal("invokedynamic bootstrap")),
                true,
                "dynamic");
        CallResolution virtualResolution = new CallResolution(
                new CallSite(
                        "virtual",
                        caller.owner(),
                        callerSignature,
                        1,
                        InvokeKind.VIRTUAL,
                        target.owner(),
                        targetSignature),
                List.of(CallTarget.known(target.owner(), targetSignature)),
                false,
                "single target");

        ProgramIrProtectionResult result =
                new ProgramIrProtectionCoordinator().run(
                        Map.of(
                                caller.methodKey(), caller,
                                target.methodKey(), target),
                        implementationPlan(caller, target),
                        new ParsedProgram(List.of()),
                        new ReflectionPlan(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        new CallGraph(List.of(dynamicResolution, virtualResolution)),
                        callIndirectionOnly(),
                        19L);

        assertTrue(result.diagnostics().isEmpty());
        ProtectionPassReport report = result.reports().stream()
                .filter(candidate -> candidate.passName().equals("IR_CALL_INDIRECTION"))
                .findFirst()
                .orElseThrow();
        assertEquals("SKIPPED", report.status());
        assertEquals(
                IrCallIndirectionReasons.BACKEND_UNSUPPORTED_SHAPE,
                report.reasonCode());
        assertEquals(2, report.coverageFacts().size());
        assertTrue(report.coverageFacts().stream()
                .allMatch(fact -> fact.requested()
                        && !fact.affected()
                        && fact.applicability()
                                == ProtectionApplicability.NOT_APPLICABLE
                        && fact.status().equals("SKIPPED")));
        assertEquals(
                IrOpcode.CALL_VIRTUAL,
                result.javaMethods()
                        .get(caller.methodKey())
                        .blocks()
                        .get(0)
                        .instructions()
                        .get(0)
                        .opcode());
    }

    private NativeImplementationPlan implementationPlan(
            IrMethod caller,
            IrMethod target) {
        return new NativeImplementationPlan(List.of(
                implementation(caller, ACC_PUBLIC | ACC_STATIC),
                implementation(target, ACC_PUBLIC)));
    }

    private NativeMethodImplementation implementation(
            IrMethod method,
            int access) {
        return implementation(method, access, false, false);
    }

    private NativeMethodImplementation implementation(
            IrMethod method,
            int access,
            boolean passesJniEnv,
            boolean passesOwnerClass) {
        ParsedMethod parsed = new ParsedMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                new AccessFlags(access),
                List.of(),
                List.of(),
                List.of(),
                true,
                method.parameters().size(),
                1,
                new MethodNode(
                        ASM9,
                        access,
                        method.name(),
                        method.descriptor(),
                        null,
                        null));
        MethodRewriteDecision decision = new MethodRewriteDecision(
                parsed,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                method.owner(),
                Optional.empty(),
                "TEST");
        NativeRegistrationEntry entry = new NativeRegistrationEntry(
                method.owner(),
                method.name(),
                method.descriptor(),
                "j2ll_test_" + method.name());
        return new NativeMethodImplementation(
                entry,
                decision,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.of("j2ll_test_impl_" + method.name()),
                "TEST",
                passesJniEnv,
                passesOwnerClass,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
    }

    private IrMethod constantMethod(String name, int value) {
        IrValue result = new IrValue("%" + name + "_result", IrType.I32);
        return new IrMethod(
                "pkg/Calls",
                name,
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.constInt(result, value)),
                        IrTerminator.returnValue(result))));
    }

    private IrProtectionConfig callIndirectionOnly() {
        return new IrProtectionConfig(
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false);
    }
}

package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrClass;
import xyz.melodysky.ir.model.IrExceptionSiteKind;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.ir.validate.IrMethodValidator;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.pipeline.LoweringStatus;
import xyz.melodysky.runtime.RuntimeTokenDomain;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jdk.JdkIntrinsic;
import xyz.melodysky.runtime.jdk.JdkIntrinsicRegistry;
import xyz.melodysky.runtime.jdk.JdkMethodPolicy;

class ClassResourceStreamJvmBridgeTest implements Opcodes {
    private static final String OWNER = "pkg/ClassResourceStreamBridge";
    private static final String TARGET =
            "java/lang/Class#getResourceAsStream!(Ljava/lang/String;)Ljava/io/InputStream;";

    @Test
    void registryUsesTheGenericJvmBridgeRatherThanAStaticRuntimeHelper() {
        JdkIntrinsic intrinsic = JdkIntrinsicRegistry.defaultRegistry()
                .lookup(
                        "java/lang/Class",
                        "getResourceAsStream",
                        "(Ljava/lang/String;)Ljava/io/InputStream;")
                .orElseThrow();

        assertEquals(JdkMethodPolicy.JVM_HELPER_BRIDGE, intrinsic.policy());
        assertTrue(intrinsic.helperKind().isEmpty());
        assertEquals(
                "JDK_BRIDGE: class-relative resource stream lookup stays on the JVM",
                intrinsic.reason());
    }

    @Test
    void ssaKeepsTheExactVirtualReceiverCallAndProtectedPendingExceptionTransfer() {
        IrMethod method = lower(parse(fixtureClass()), "loadOrNull");
        IrInstruction call = resourceCall(method);

        assertEquals(IrOpcode.CALL_VIRTUAL, call.opcode());
        assertEquals(TARGET, call.symbol().orElseThrow());
        assertEquals(
                List.of(IrType.REFERENCE, IrType.REFERENCE),
                call.operands().stream().map(value -> value.type()).toList());
        assertEquals(IrType.REFERENCE, call.result().orElseThrow().type());
        assertEquals(1, call.exceptionSites().size());
        assertEquals(
                IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                call.exceptionSites().get(0).kind());
        assertEquals(
                List.of("java/lang/NullPointerException"),
                call.exceptionSites().get(0).handlers().stream()
                        .map(edge -> edge.catchType())
                        .toList());
        var pendingException = call.exceptionSites().get(0).exceptionValue().orElseThrow();
        assertEquals(IrType.REFERENCE, pendingException.type());
        assertEquals(
                pendingException,
                call.exceptionSites().get(0).handlers().get(0).arguments().get(0));
        assertTrue(new IrMethodValidator().validate(method).isEmpty());
        assertFalse(new NativeExceptionFlowSupport().hasUnsupportedJvmFlow(method));
    }

    @Test
    void plannerAndLlvmUseTheTokenizedGenericDispatchAndPreserveExceptionChecks() {
        ParsedClass parsedClass = parse(fixtureClass());
        MethodRewriteDecision decision = decision(parsedClass, "loadOrNull");
        IrMethod method = lower(parsedClass, "loadOrNull");
        NativeRegistrationPlan registration =
                new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registration,
                List.of(decision),
                Map.of(decision.method().methodKey(), method));
        NativeMethodImplementation implementation =
                plan.implementationFor(decision.method().methodKey()).orElseThrow();

        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_DISPATCH_HELPER_IR", implementation.reasonCode());
        assertEquals(List.of(TARGET), implementation.dispatchKeys());
        assertTrue(implementation.passesJniEnv());

        String llvm = new LlvmTextEmitter().emit(
                xyz.melodysky.testsupport.TestProtectionMaterials.llvmLowerer().lowerClass(new IrClass(OWNER, List.of(method))));
        String bridgeHelper = xyz.melodysky.testsupport.TestProtectionMaterials.runtimeTokens().helperSymbol(
                RuntimeTokenDomain.DISPATCH_METHOD,
                "virtual_dispatch_ref",
                TARGET);
        String catchHelper = xyz.melodysky.testsupport.TestProtectionMaterials.runtimeTokens().helperSymbol(
                RuntimeTokenDomain.CLASS_RUNTIME,
                "instanceof",
                "instanceof:java/lang/NullPointerException");
        int bridgeCall = llvm.indexOf("call ptr @" + bridgeHelper + "(");
        int pendingCheck = llvm.indexOf(
                "call ptr @j2ll_rt_pending_exception(ptr %j2ll_env)",
                bridgeCall);
        int clear = llvm.indexOf(
                "call void @j2ll_rt_clear_exception(ptr %j2ll_env)",
                pendingCheck);
        int typedDispatch = llvm.indexOf(
                "call i32 @" + catchHelper + "(",
                clear);

        assertTrue(bridgeCall >= 0, llvm);
        assertTrue(pendingCheck > bridgeCall, llvm);
        assertTrue(clear > pendingCheck, llvm);
        assertTrue(typedDispatch > clear, llvm);
        assertTrue(llvm.contains("call void @j2ll_rt_rethrow("), llvm);
        assertFalse(llvm.contains("getResourceAsStream"), llvm);
    }

    private ParsedClass parse(byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(OWNER + ".class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private MethodRewriteDecision decision(ParsedClass parsedClass, String methodName) {
        return new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod lower(ParsedClass parsedClass, String methodName) {
        ParsedMethod method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals(methodName))
                .findFirst()
                .orElseThrow();
        var stage = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer().lower(
                new MethodCfgBuilder().build(method).artifact().orElseThrow());
        assertFalse(stage.hasErrors(), stage.diagnostics().toString());
        var lowered = stage.artifact().orElseThrow();
        assertEquals(LoweringStatus.NATIVE_LOWERED, lowered.status(), lowered.reason());
        return lowered.irMethod().orElseThrow();
    }

    private IrInstruction resourceCall(IrMethod method) {
        return method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.symbol().map(TARGET::equals).orElse(false))
                .findFirst()
                .orElseThrow();
    }

    private byte[] fixtureClass() {
        ClassWriter writer =
                new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, OWNER, null, "java/lang/Object", null);

        MethodVisitor load = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "load",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;",
                null,
                null);
        load.visitCode();
        emitResourceCall(load);
        load.visitInsn(ARETURN);
        load.visitMaxs(0, 0);
        load.visitEnd();

        MethodVisitor protectedLoad = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "loadOrNull",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/io/InputStream;",
                null,
                null);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        protectedLoad.visitTryCatchBlock(
                start,
                end,
                handler,
                "java/lang/NullPointerException");
        protectedLoad.visitCode();
        protectedLoad.visitLabel(start);
        emitResourceCall(protectedLoad);
        protectedLoad.visitLabel(end);
        protectedLoad.visitInsn(ARETURN);
        protectedLoad.visitLabel(handler);
        protectedLoad.visitInsn(POP);
        protectedLoad.visitInsn(ACONST_NULL);
        protectedLoad.visitInsn(ARETURN);
        protectedLoad.visitMaxs(0, 0);
        protectedLoad.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitResourceCall(MethodVisitor method) {
        method.visitVarInsn(ALOAD, 0);
        method.visitVarInsn(ALOAD, 1);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getResourceAsStream",
                "(Ljava/lang/String;)Ljava/io/InputStream;",
                false);
    }
}

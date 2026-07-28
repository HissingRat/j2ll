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
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.pipeline.LoweringStatus;

class ThreadSleepNativeHelperTest implements Opcodes {
    @Test
    void plansAndLowersThreadSleepWithProtectedInterruptedExceptionFlow() {
        ParsedClass parsedClass = parse(threadSleepClass());
        MethodRewriteDecision decision = decision(parsedClass);
        IrMethod irMethod = irMethod(parsedClass);
        IrInstruction sleep = irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                .filter(instruction -> instruction.symbol()
                        .map("j2ll_rt_thread_sleep"::equals)
                        .orElse(false))
                .findFirst()
                .orElseThrow();

        assertEquals(1, sleep.exceptionSites().size());
        assertEquals(IrExceptionSiteKind.JVM_PENDING_EXCEPTION, sleep.exceptionSites().get(0).kind());
        assertEquals(
                "java/lang/InterruptedException",
                sleep.exceptionSites().get(0).handlers().get(0).catchType());

        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan plan = new NativeImplementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));
        NativeMethodImplementation implementation =
                plan.implementationFor(decision.method().methodKey()).orElseThrow();

        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_JDK_INTRINSIC_HELPER_IR", implementation.reasonCode());
        assertTrue(implementation.passesJniEnv());

        String llvm = new LlvmTextEmitter().emit(
                new LlvmModuleLowerer().lowerClass(new IrClass("pkg/ThreadSleep", List.of(irMethod))));
        assertTrue(llvm.contains(
                "declare void @j2ll_rt_thread_sleep(ptr, i64) ; threadSleep"));
        assertTrue(llvm.contains(
                "call void @j2ll_rt_thread_sleep(ptr %j2ll_env, i64 %p0)"));
        assertTrue(llvm.contains(
                "call ptr @j2ll_rt_pending_exception(ptr %j2ll_env)"));
    }

    @Test
    void hostHelperDelegatesToTheJvmAndLeavesAnyExceptionPending() {
        String source = HostJniThreadRuntimeSource.threadHelperSource();

        assertTrue(source.contains(
                "(*env)->FindClass(env, \"java/lang/Thread\")"));
        assertTrue(source.contains(
                "(*env)->GetStaticMethodID("));
        assertTrue(source.contains("\"sleep\""));
        assertTrue(source.contains("\"(J)V\""));
        assertTrue(source.contains("arguments[0].j = (jlong)millis;"));
        assertTrue(source.contains("(*env)->CallStaticVoidMethodA("));
        assertTrue(source.contains("(*env)->DeleteLocalRef(env, thread_class);"));
        assertFalse(source.contains("ExceptionClear"));
        assertFalse(source.contains("NewGlobalRef"));
    }

    private ParsedClass parse(byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry("pkg/ThreadSleep.class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private MethodRewriteDecision decision(ParsedClass parsedClass) {
        return new MethodRewritePlanner().planClass(parsedClass).stream()
                .filter(item -> item.method().name().equals("sleepAndReport"))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod irMethod(ParsedClass parsedClass) {
        ParsedMethod method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals("sleepAndReport"))
                .findFirst()
                .orElseThrow();
        var stage = new BytecodeToSsaLowerer()
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow());
        assertFalse(stage.hasErrors(), stage.diagnostics().toString());
        var lowered = stage.artifact().orElseThrow();
        assertEquals(LoweringStatus.NATIVE_LOWERED, lowered.status(), lowered.reason());
        return lowered.irMethod().orElseThrow();
    }

    private byte[] threadSleepClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ThreadSleep", null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "sleepAndReport",
                "(J)Z",
                null,
                null);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/InterruptedException");
        method.visitCode();
        method.visitLabel(start);
        method.visitVarInsn(LLOAD, 0);
        method.visitMethodInsn(INVOKESTATIC, "java/lang/Thread", "sleep", "(J)V", false);
        method.visitLabel(end);
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitLabel(handler);
        method.visitInsn(POP);
        method.visitInsn(ICONST_0);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}

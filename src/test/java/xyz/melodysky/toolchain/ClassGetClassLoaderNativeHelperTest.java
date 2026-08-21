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
import xyz.melodysky.runtime.RuntimeHelper;
import xyz.melodysky.runtime.RuntimeHelperCatalog;
import xyz.melodysky.runtime.RuntimeHelperCategory;
import xyz.melodysky.runtime.RuntimeHelperKind;
import xyz.melodysky.runtime.jdk.JdkIntrinsic;
import xyz.melodysky.runtime.jdk.JdkIntrinsicRegistry;
import xyz.melodysky.runtime.jdk.JdkMethodPolicy;

class ClassGetClassLoaderNativeHelperTest implements Opcodes {
    @Test
    void registryAndCatalogExposeTheExactEnvBackedHelperAbi() {
        JdkIntrinsic intrinsic = JdkIntrinsicRegistry.defaultRegistry()
                .lookup("java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;")
                .orElseThrow();
        assertEquals(JdkMethodPolicy.RUNTIME_HELPER, intrinsic.policy());
        assertEquals(RuntimeHelperKind.CLASS_GET_CLASS_LOADER, intrinsic.helperKind().orElseThrow());

        RuntimeHelper helper = RuntimeHelperCatalog.defaultCatalog()
                .helper(RuntimeHelperKind.CLASS_GET_CLASS_LOADER)
                .orElseThrow();
        assertEquals(RuntimeHelperCategory.JDK_INTRINSIC, helper.category());
        assertEquals("j2ll_rt_class_get_class_loader", helper.llvmSymbol());
        assertEquals("ptr", helper.llvmReturnType());
        assertEquals(List.of("ptr", "ptr"), helper.llvmParameterTypes());
    }

    @Test
    void lowersProtectedSecurityExceptionFlowThroughTheEnvBackedHelper() {
        ParsedClass parsedClass = parse(classLoaderHelperClass());
        MethodRewriteDecision decision = decision(parsedClass);
        IrMethod irMethod = irMethod(parsedClass);
        IrInstruction helperCall = irMethod.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_RUNTIME_HELPER)
                .filter(instruction -> instruction.symbol()
                        .map("j2ll_rt_class_get_class_loader"::equals)
                        .orElse(false))
                .findFirst()
                .orElseThrow();

        assertEquals(1, helperCall.exceptionSites().size());
        assertEquals(
                IrExceptionSiteKind.JVM_PENDING_EXCEPTION,
                helperCall.exceptionSites().get(0).kind());
        assertEquals(
                "java/lang/SecurityException",
                helperCall.exceptionSites().get(0).handlers().get(0).catchType());

        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));
        NativeMethodImplementation implementation =
                plan.implementationFor(decision.method().methodKey()).orElseThrow();

        assertEquals(NativeImplementationPath.LLVM_NATIVE_PATH, implementation.path());
        assertEquals("LLVM_JDK_INTRINSIC_HELPER_IR", implementation.reasonCode());
        assertTrue(implementation.passesJniEnv());

        String llvm = new LlvmTextEmitter().emit(
                xyz.melodysky.testsupport.TestProtectionMaterials.llvmLowerer().lowerClass(
                        new IrClass("pkg/ClassLoaderHelpers", List.of(irMethod))));
        assertTrue(llvm.contains(
                "declare ptr @j2ll_rt_class_get_class_loader(ptr, ptr) ; classGetClassLoader"));
        assertTrue(llvm.contains(
                "call ptr @j2ll_rt_class_get_class_loader(ptr %j2ll_env, ptr %p0)"));
        assertTrue(llvm.contains(
                "call ptr @j2ll_rt_pending_exception(ptr %j2ll_env)"));
    }

    @Test
    void hostHelperCallsTheJvmWithoutCachingOrClearingPendingExceptions() {
        String source = HostJniJdkObjectRuntimeSource.jdkObjectHelperSource();
        int start = source.indexOf("jobject j2ll_rt_class_get_class_loader");
        int end = source.indexOf("static jobject j2ll_call_static_box", start);
        String helper = source.substring(start, end);

        assertTrue(helper.contains("if (value == NULL)"));
        assertTrue(helper.contains(
                "j2ll_throw_new(env, \"java/lang/NullPointerException\", \"Class.getClassLoader receiver is null\")"));
        assertTrue(helper.contains("(*env)->GetObjectClass(env, value)"));
        assertTrue(helper.contains("\"getClassLoader\""));
        assertTrue(helper.contains("\"()Ljava/lang/ClassLoader;\""));
        assertTrue(helper.contains("(*env)->CallObjectMethod(env, value, method)"));
        assertTrue(helper.contains("(*env)->DeleteLocalRef(env, class_class)"));
        assertFalse(helper.contains("ExceptionClear"));
        assertFalse(helper.contains("NewGlobalRef"));
        assertFalse(helper.contains("static jclass"));
        assertFalse(helper.contains("static jmethodID"));
    }

    private ParsedClass parse(byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry("pkg/ClassLoaderHelpers.class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private MethodRewriteDecision decision(ParsedClass parsedClass) {
        return new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals("loaderOf"))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod irMethod(ParsedClass parsedClass) {
        ParsedMethod method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals("loaderOf"))
                .findFirst()
                .orElseThrow();
        var stage = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow());
        assertFalse(stage.hasErrors(), stage.diagnostics().toString());
        var lowered = stage.artifact().orElseThrow();
        assertEquals(LoweringStatus.NATIVE_LOWERED, lowered.status(), lowered.reason());
        return lowered.irMethod().orElseThrow();
    }

    private byte[] classLoaderHelperClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ClassLoaderHelpers", null, "java/lang/Object", null);

        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "loaderOf",
                "(Ljava/lang/Class;)Ljava/lang/ClassLoader;",
                null,
                null);
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/SecurityException");
        method.visitCode();
        method.visitLabel(start);
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Class",
                "getClassLoader",
                "()Ljava/lang/ClassLoader;",
                false);
        method.visitLabel(end);
        method.visitInsn(ARETURN);
        method.visitLabel(handler);
        method.visitInsn(POP);
        method.visitInsn(ACONST_NULL);
        method.visitInsn(ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}

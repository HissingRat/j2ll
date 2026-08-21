package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
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
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;

class ObjectGetClassNativeHelperTest implements Opcodes {
    @Test
    void plansAndLowersObjectGetClassThroughTheEnvBackedHelper() {
        ParsedClass parsedClass = parse(objectHelperClass());
        MethodRewriteDecision decision = decision(parsedClass, "typeOf");
        IrMethod irMethod = irMethod(parsedClass, "typeOf");
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
                xyz.melodysky.testsupport.TestProtectionMaterials.llvmLowerer().lowerClass(new IrClass("pkg/ObjectHelpers", List.of(irMethod))));
        assertTrue(llvm.contains(
                "declare ptr @j2ll_rt_object_get_class(ptr, ptr) ; objectGetClass"));
        assertTrue(llvm.contains(
                "call ptr @j2ll_rt_object_get_class(ptr %j2ll_env, ptr %p0)"));
    }

    @Test
    void leavesOtherUnimplementedObjectHelpersOutsideThePlannerWhitelist() {
        ParsedClass parsedClass = parse(objectHelperClass());
        MethodRewriteDecision decision = decision(parsedClass, "objectHash");
        IrMethod irMethod = irMethod(parsedClass, "objectHash");
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));

        NativeImplementationPlan plan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                List.of(decision),
                Map.of(decision.method().methodKey(), irMethod));

        assertTrue(plan.implementations().isEmpty());
    }

    @Test
    void hostHelperPreservesPendingExceptionsAndReturnsTheLocalClassReference() {
        String source = HostJniJdkObjectRuntimeSource.jdkObjectHelperSource();
        int start = source.indexOf("jobject j2ll_rt_object_get_class");
        int end = source.indexOf("jobject j2ll_rt_class_get_class_loader", start);
        String helper = source.substring(start, end);

        assertTrue(helper.contains("if (value == NULL)"));
        assertTrue(helper.contains(
                "j2ll_throw_new(env, \"java/lang/NullPointerException\", \"Object.getClass receiver is null\")"));
        assertTrue(helper.contains("return (*env)->GetObjectClass(env, value);"));
        assertFalse(helper.contains("ExceptionClear"));
        assertFalse(helper.contains("DeleteLocalRef"));
    }

    private ParsedClass parse(byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry("pkg/ObjectHelpers.class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private MethodRewriteDecision decision(ParsedClass parsedClass, String name) {
        return new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod irMethod(ParsedClass parsedClass, String name) {
        ParsedMethod method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        return xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder().build(method).artifact().orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
    }

    private byte[] objectHelperClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/ObjectHelpers", null, "java/lang/Object", null);

        MethodVisitor typeOf = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "typeOf",
                "(Ljava/lang/Object;)Ljava/lang/Class;",
                null,
                null);
        typeOf.visitCode();
        typeOf.visitVarInsn(ALOAD, 0);
        typeOf.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Object",
                "getClass",
                "()Ljava/lang/Class;",
                false);
        typeOf.visitInsn(ARETURN);
        typeOf.visitMaxs(0, 0);
        typeOf.visitEnd();

        MethodVisitor objectHash = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "objectHash",
                "(Ljava/lang/Object;)I",
                null,
                null);
        objectHash.visitCode();
        objectHash.visitVarInsn(ALOAD, 0);
        objectHash.visitMethodInsn(
                INVOKEVIRTUAL,
                "java/lang/Object",
                "hashCode",
                "()I",
                false);
        objectHash.visitInsn(IRETURN);
        objectHash.visitMaxs(0, 0);
        objectHash.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}

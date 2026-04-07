package xyz.melodysky.frontend.bytecode;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassIrBuilderTest {

    private static final org.objectweb.asm.Handle LAMBDA_METAFACTORY = new org.objectweb.asm.Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory",
            "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
            false
    );

    private static final Handle LAMBDA_IMPL = new Handle(
            Opcodes.H_INVOKESTATIC,
            "sample/Example",
            "lambdaTarget",
            "(Ljava/lang/Enum;Ljava/lang/Enum;)V",
            false
    );

    @Test
    public void testBuildsSupportedMethodsAndRecordsSkippedOnes() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode supported = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "add", "(II)I", null, null);
        supported.maxLocals = 2;
        supported.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        supported.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        supported.instructions.add(new InsnNode(Opcodes.IADD));
        supported.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode nowSupported = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        nowSupported.maxLocals = 0;
        nowSupported.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Utils", "touch", "()V", false));
        nowSupported.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode unsupported = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "branchWithStack", "()I", null, null);
        unsupported.maxLocals = 0;
        LabelNode target = new LabelNode();
        unsupported.instructions.add(new InsnNode(Opcodes.ICONST_1));
        unsupported.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, target));
        unsupported.instructions.add(target);
        unsupported.instructions.add(new InsnNode(Opcodes.IRETURN));

        MethodNode abstractMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "abstractMethod", "()V", null, null);

        classNode.methods.add(supported);
        classNode.methods.add(nowSupported);
        classNode.methods.add(unsupported);
        classNode.methods.add(abstractMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals("sample/Example", result.irClass().reference().internalName());
        assertEquals(3, result.irClass().methods().size());
        assertEquals("add", result.irClass().methods().get(0).name());
        assertEquals("call", result.irClass().methods().get(1).name());
        assertEquals("branchWithStack", result.irClass().methods().get(2).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithTypedTryCatchAroundInvokeInterface() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";
        classNode.interfaces.add("java/lang/Runnable");

        MethodNode protectedMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        protectedMethod.instructions.add(start);
        protectedMethod.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        protectedMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        protectedMethod.instructions.add(end);
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.instructions.add(handler);
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));

        classNode.methods.add(protectedMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("guarded", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithAnyHandlerAroundInvokeInterface() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";
        classNode.interfaces.add("java/util/concurrent/ExecutorService");

        MethodNode protectedMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "(Ljava/util/concurrent/ExecutorService;)V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        protectedMethod.maxLocals = 2;
        protectedMethod.instructions.add(start);
        protectedMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        protectedMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/concurrent/ExecutorService", "shutdownNow", "()Ljava/util/List;", true));
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(end);
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.instructions.add(handler);
        protectedMethod.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        protectedMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        protectedMethod.instructions.add(new InsnNode(Opcodes.ATHROW));
        protectedMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));

        classNode.methods.add(protectedMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("guarded", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithTypedTryCatchAroundLibraryInvoke() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode protectedMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "()I", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        protectedMethod.maxLocals = 1;
        protectedMethod.instructions.add(start);
        protectedMethod.instructions.add(new LdcInsnNode("42"));
        protectedMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false));
        protectedMethod.instructions.add(end);
        protectedMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        protectedMethod.instructions.add(handler);
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        protectedMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        protectedMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));

        classNode.methods.add(protectedMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("guarded", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithBroadTryCatchAndExplicitThrow() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode protectedMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "(Z)I", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();
        protectedMethod.maxLocals = 1;
        protectedMethod.instructions.add(start);
        protectedMethod.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        protectedMethod.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
        protectedMethod.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        protectedMethod.instructions.add(new InsnNode(Opcodes.DUP));
        protectedMethod.instructions.add(new LdcInsnNode("boom"));
        protectedMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        protectedMethod.instructions.add(new InsnNode(Opcodes.ATHROW));
        protectedMethod.instructions.add(end);
        protectedMethod.instructions.add(done);
        protectedMethod.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        protectedMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        protectedMethod.instructions.add(handler);
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        protectedMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        protectedMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        classNode.methods.add(protectedMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("guarded", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithBroadTryCatchAndInvokeInterface() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";
        classNode.interfaces.add("java/lang/Runnable");

        MethodNode protectedMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "(Ljava/lang/Runnable;)V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        protectedMethod.maxLocals = 1;
        protectedMethod.instructions.add(start);
        protectedMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        protectedMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        protectedMethod.instructions.add(end);
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.instructions.add(handler);
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        classNode.methods.add(protectedMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("guarded", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithBroadTryCatchAndInvokeDynamic() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode protectedMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        protectedMethod.instructions.add(start);
        protectedMethod.instructions.add(new InvokeDynamicInsnNode(
                "run",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("()V"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Helpers",
                        "run",
                        "()V",
                        false
                ),
                Type.getMethodType("()V")
        ));
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(end);
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.instructions.add(handler);
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

        classNode.methods.add(protectedMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("guarded", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithTypedTryCatchAndInvokeDynamic() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode protectedMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "()V", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        protectedMethod.instructions.add(start);
        protectedMethod.instructions.add(new InvokeDynamicInsnNode(
                "run",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("()V"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Helpers",
                        "run",
                        "()V",
                        false
                ),
                Type.getMethodType("()V")
        ));
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(end);
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.instructions.add(handler);
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(new InsnNode(Opcodes.RETURN));
        protectedMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));

        classNode.methods.add(protectedMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("guarded", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithTypedTryCatchAroundSameClassInvoke() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "helper", "()V", null, null);
        helper.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode protectedMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "guarded", "()I", null, null);
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        protectedMethod.instructions.add(start);
        protectedMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Example", "helper", "()V", false));
        protectedMethod.instructions.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        protectedMethod.instructions.add(end);
        protectedMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        protectedMethod.instructions.add(handler);
        protectedMethod.instructions.add(new InsnNode(Opcodes.POP));
        protectedMethod.instructions.add(new InsnNode(Opcodes.ICONST_M1));
        protectedMethod.instructions.add(new InsnNode(Opcodes.IRETURN));
        protectedMethod.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/RuntimeException"));

        classNode.methods.add(helper);
        classNode.methods.add(protectedMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsEnumPlumbingAndBusinessMethods() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Mode";
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ENUM;

        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Mode", "values", "()[Lsample/Mode;", false));
        clinit.instructions.add(new InsnNode(Opcodes.POP));
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(clinit);

        MethodNode values = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()[Lsample/Mode;", null, null);
        values.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        values.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(values);

        MethodNode apply = new MethodNode(Opcodes.ACC_PUBLIC, "apply", "(II)I", null, null);
        apply.maxLocals = 3;
        apply.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        apply.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
        apply.instructions.add(new InsnNode(Opcodes.IADD));
        apply.instructions.add(new InsnNode(Opcodes.IRETURN));
        classNode.methods.add(apply);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(3, result.irClass().methods().size());
        assertEquals("<clinit>", result.irClass().methods().get(0).name());
        assertEquals("values", result.irClass().methods().get(1).name());
        assertEquals("apply", result.irClass().methods().get(2).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testSkipsAnnotationClasses() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/BenchTag";
        classNode.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;

        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(clinit);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertTrue(result.irClass().methods().isEmpty());
        assertEquals(1, result.skippedMethods().size());
        assertEquals("annotation classes are not native-lowered yet", result.skippedMethods().get(0).reason());
    }

    @Test
    public void testBuildsMethodsWithLambdaInvokeDynamic() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode lambdaCaller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lambdaCaller", "()V", null, null);
        lambdaCaller.instructions.add(new InvokeDynamicInsnNode(
                "run",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("()V"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Helpers",
                        "run",
                        "()V",
                        false
                ),
                Type.getMethodType("()V")
        ));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.POP));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.RETURN));
        classNode.methods.add(lambdaCaller);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("lambdaCaller", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testSkipsLambdaImplementationTargetsReferencedByInvokeDynamic() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode lambdaCaller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lambdaCaller", "()V", null, null);
        lambdaCaller.instructions.add(new InvokeDynamicInsnNode(
                "perform",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;Ljava/lang/Object;)V"),
                LAMBDA_IMPL,
                org.objectweb.asm.Type.getMethodType("(Ljava/lang/Enum;Ljava/lang/Enum;)V")
        ));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.POP));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode lambdaTarget = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambdaTarget", "(Ljava/lang/Enum;Ljava/lang/Enum;)V", null, null);
        lambdaTarget.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(lambdaCaller);
        classNode.methods.add(lambdaTarget);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsSafeSyntheticLambdaImplementationTargets() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode lambdaCaller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lambdaCaller", "()V", null, null);
        lambdaCaller.instructions.add(new InvokeDynamicInsnNode(
                "perform",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                org.objectweb.asm.Type.getMethodType("()V"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Example",
                        "lambda$lambdaCaller$0",
                        "()V",
                        false
                ),
                org.objectweb.asm.Type.getMethodType("()V")
        ));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.POP));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode lambdaTarget = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$lambdaCaller$0",
                "()V",
                null,
                null
        );
        lambdaTarget.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(lambdaCaller);
        classNode.methods.add(lambdaTarget);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsPrivateStaticMethodReferenceTargets() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode lambdaCaller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lambdaCaller", "()V", null, null);
        lambdaCaller.instructions.add(new InvokeDynamicInsnNode(
                "accept",
                "()Ljava/util/function/BiConsumer;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("(Ljava/lang/Object;Ljava/lang/Object;)V"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Example",
                        "target",
                        "(Ljava/lang/String;Ljava/lang/String;)V",
                        false
                ),
                Type.getMethodType("(Ljava/lang/String;Ljava/lang/String;)V")
        ));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.POP));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode lambdaTarget = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "target",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                null,
                null
        );
        lambdaTarget.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(lambdaCaller);
        classNode.methods.add(lambdaTarget);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsSyntheticLambdaImplementationTargetsWithExplicitThrow() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode lambdaCaller = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "lambdaCaller", "()V", null, null);
        lambdaCaller.instructions.add(new InvokeDynamicInsnNode(
                "run",
                "()Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("()V"),
                new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "sample/Example",
                        "lambda$lambdaCaller$0",
                        "()V",
                        false
                ),
                Type.getMethodType("()V")
        ));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.POP));
        lambdaCaller.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode lambdaTarget = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "lambda$lambdaCaller$0",
                "()V",
                null,
                null
        );
        lambdaTarget.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        lambdaTarget.instructions.add(new InsnNode(Opcodes.DUP));
        lambdaTarget.instructions.add(new LdcInsnNode("boom"));
        lambdaTarget.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false));
        lambdaTarget.instructions.add(new InsnNode(Opcodes.ATHROW));

        classNode.methods.add(lambdaCaller);
        classNode.methods.add(lambdaTarget);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsMethodsWithCharArrayOrStringBuilderProcessing() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode stringMethod = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "strip", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        stringMethod.maxLocals = 2;
        stringMethod.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        stringMethod.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
        stringMethod.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        stringMethod.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        stringMethod.instructions.add(new InsnNode(Opcodes.ARETURN));
        classNode.methods.add(stringMethod);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(1, result.irClass().methods().size());
        assertEquals("strip", result.irClass().methods().get(0).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsClassInitializerWithConstructorLambdaReference() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.maxLocals = 1;
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));

        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.maxLocals = 0;
        clinit.instructions.add(new InvokeDynamicInsnNode(
                "get",
                "()Ljava/util/function/Supplier;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("()Ljava/lang/Object;"),
                new Handle(
                        Opcodes.H_NEWINVOKESPECIAL,
                        "sample/Example",
                        "<init>",
                        "()V",
                        false
                ),
                Type.getMethodType("()Lsample/Example;")
        ));
        clinit.instructions.add(new InsnNode(Opcodes.POP));
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(constructor);
        classNode.methods.add(clinit);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testSkipsMethodsCallingSameClassSkippedMethods() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";
        classNode.interfaces.add("java/lang/Runnable");

        MethodNode strip = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "strip", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        strip.maxLocals = 1;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        strip.instructions.add(start);
        strip.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        strip.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        strip.instructions.add(end);
        strip.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        strip.instructions.add(new InsnNode(Opcodes.ARETURN));
        strip.instructions.add(handler);
        strip.instructions.add(new InsnNode(Opcodes.POP));
        strip.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        strip.instructions.add(new InsnNode(Opcodes.ARETURN));
        strip.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));

        MethodNode render = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "render", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        render.maxLocals = 1;
        render.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        render.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "sample/Example", "strip", "(Ljava/lang/String;)Ljava/lang/String;", false));
        render.instructions.add(new InsnNode(Opcodes.ARETURN));

        classNode.methods.add(strip);
        classNode.methods.add(render);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertEquals("strip", result.irClass().methods().get(0).name());
        assertEquals("render", result.irClass().methods().get(1).name());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsLambdaCallersReferencingSafePrivateInstanceMethodTargets() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode target = new MethodNode(Opcodes.ACC_PRIVATE, "target", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        target.maxLocals = 2;
        target.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        target.instructions.add(new InsnNode(Opcodes.ARETURN));

        MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC, "caller", "()V", null, null);
        caller.maxLocals = 1;
        caller.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        caller.instructions.add(new InvokeDynamicInsnNode(
                "apply",
                "(Lsample/Example;)Ljava/util/function/Function;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(
                        Opcodes.H_INVOKEVIRTUAL,
                        "sample/Example",
                        "target",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false
                ),
                Type.getMethodType("(Ljava/lang/String;)Ljava/lang/String;")
        ));
        caller.instructions.add(new InsnNode(Opcodes.POP));
        caller.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(target);
        classNode.methods.add(caller);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testSkipsPrivateInstanceMethodReferenceTargetsWithTryCatch() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";

        MethodNode target = new MethodNode(Opcodes.ACC_PRIVATE, "target", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        target.maxLocals = 2;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        target.instructions.add(start);
        target.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        target.instructions.add(end);
        target.instructions.add(new InsnNode(Opcodes.ARETURN));
        target.instructions.add(handler);
        target.instructions.add(new InsnNode(Opcodes.POP));
        target.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        target.instructions.add(new InsnNode(Opcodes.ARETURN));
        target.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));

        MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC, "caller", "()V", null, null);
        caller.maxLocals = 1;
        caller.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        caller.instructions.add(new InvokeDynamicInsnNode(
                "apply",
                "(Lsample/Example;)Ljava/util/function/Function;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(
                        Opcodes.H_INVOKEVIRTUAL,
                        "sample/Example",
                        "target",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false
                ),
                Type.getMethodType("(Ljava/lang/String;)Ljava/lang/String;")
        ));
        caller.instructions.add(new InsnNode(Opcodes.POP));
        caller.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(target);
        classNode.methods.add(caller);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

    @Test
    public void testBuildsPrivateInstanceMethodReferenceTargetsWithTypedTryCatchAroundInvokeInterface() {
        ClassNode classNode = new ClassNode(Opcodes.ASM9);
        classNode.name = "sample/Example";
        classNode.interfaces.add("java/lang/Runnable");

        MethodNode target = new MethodNode(Opcodes.ACC_PRIVATE, "target", "()V", null, null);
        target.maxLocals = 1;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        target.instructions.add(start);
        target.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        target.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
        target.instructions.add(end);
        target.instructions.add(new InsnNode(Opcodes.RETURN));
        target.instructions.add(handler);
        target.instructions.add(new InsnNode(Opcodes.POP));
        target.instructions.add(new InsnNode(Opcodes.RETURN));
        target.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Exception"));

        MethodNode caller = new MethodNode(Opcodes.ACC_PUBLIC, "caller", "()V", null, null);
        caller.maxLocals = 1;
        caller.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        caller.instructions.add(new InvokeDynamicInsnNode(
                "run",
                "(Lsample/Example;)Ljava/lang/Runnable;",
                LAMBDA_METAFACTORY,
                Type.getMethodType("()V"),
                new Handle(
                        Opcodes.H_INVOKEVIRTUAL,
                        "sample/Example",
                        "target",
                        "()V",
                        false
                ),
                Type.getMethodType("()V")
        ));
        caller.instructions.add(new InsnNode(Opcodes.POP));
        caller.instructions.add(new InsnNode(Opcodes.RETURN));

        classNode.methods.add(target);
        classNode.methods.add(caller);

        ClassIrBuilder.BuildResult result = new ClassIrBuilder().build(classNode);

        assertEquals(2, result.irClass().methods().size());
        assertTrue(result.skippedMethods().isEmpty());
    }

}

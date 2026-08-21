package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.InterfaceMethodAsmFixtures;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlanner;

class NativeOriginalClassRewriterTest implements Opcodes {
    private final MethodRewritePlanner planner = new MethodRewritePlanner();

    @Test
    void nativeOriginalRemovesCodeAndSetsNativeFlag() {
        ParsedClass parsedClass = parsed("pkg/Mathy", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"));
        MethodRewriteDecision decision = planner.planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals("add"))
                .findFirst()
                .orElseThrow();

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(parsedClass, List.of(decision));
        ParsedMethod rewritten = parsed("pkg/Mathy", result.classBytes()).methods().stream()
                .filter(method -> method.name().equals("add"))
                .findFirst()
                .orElseThrow();

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(1, result.applied().size());
        assertTrue(rewritten.accessFlags().isNative());
        assertFalse(rewritten.hasCode());
        assertEquals("(II)I", rewritten.descriptor());
    }

    @Test
    void nativeOriginalCanInjectLoaderTriggerIntoGeneratedClassInitializer() {
        ParsedClass parsedClass = parsed("pkg/Mathy", AsmFixtureBuilder.classWithAddMethod("pkg/Mathy"));
        MethodRewriteDecision decision = planner.planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals("add"))
                .findFirst()
                .orElseThrow();

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(
                parsedClass,
                List.of(decision),
                "native0/Loader");
        ParsedClass rewritten = parsed("pkg/Mathy", result.classBytes());
        MethodNode clinit = rewritten.classNode().methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow();
        MethodInsnNode trigger = (MethodInsnNode) clinit.instructions.getFirst();

        assertEquals(INVOKESTATIC, trigger.getOpcode());
        assertEquals("native0/Loader", trigger.owner);
        assertEquals("ensureLoaded", trigger.name);
        assertEquals("()V", trigger.desc);
    }

    @Test
    void defaultInterfaceMethodBecomesLoaderBackedHelperStub() {
        ParsedClass parsedClass = parsed(
                "pkg/Api",
                AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"));
        MethodRewriteDecision decision = planner.planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals("answer"))
                .findFirst()
                .orElseThrow();

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(
                parsedClass,
                List.of(decision),
                "native0/Loader");
        ParsedClass rewritten = parsed("pkg/Api", result.classBytes());
        MethodNode answer = rewritten.classNode().methods.stream()
                .filter(method -> method.name.equals("answer"))
                .findFirst()
                .orElseThrow();
        List<AbstractInsnNode> opcodes = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                answer.instructions.iterator(),
                                java.util.Spliterator.ORDERED),
                        false)
                .filter(instruction -> instruction.getOpcode() >= 0)
                .toList();

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(1, result.applied().size());
        assertFalse((answer.access & ACC_NATIVE) != 0);
        assertEquals(4, opcodes.size());
        MethodInsnNode loader = (MethodInsnNode) opcodes.get(0);
        assertEquals("native0/Loader", loader.owner);
        assertEquals("ensureLoaded", loader.name);
        assertEquals(ALOAD, opcodes.get(1).getOpcode());
        assertEquals(0, ((VarInsnNode) opcodes.get(1)).var);
        MethodInsnNode helper = (MethodInsnNode) opcodes.get(2);
        assertEquals(decision.registrationOwner(), helper.owner);
        assertEquals(decision.generatedHelperName().orElseThrow(), helper.name);
        assertEquals("(Lpkg/Api;)I", helper.desc);
        assertEquals(IRETURN, opcodes.get(3).getOpcode());
        assertTrue(rewritten.classNode().methods.stream()
                .noneMatch(method -> method.name.equals(
                        decision.generatedHelperName().orElseThrow())));
    }

    @Test
    void staticAndPrivateInterfaceStubsUseCorrectLocalLayout() {
        ParsedClass parsedClass = parsed(
                "pkg/CodeApi",
                InterfaceMethodAsmFixtures.interfaceWithDefaultStaticAndPrivate(
                        "pkg/CodeApi"));
        var decisions = planner.planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(decision -> decision.method().name().equals("staticAnswer")
                        || decision.method().name().equals("privateAnswer"))
                .toList();

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(
                parsedClass,
                decisions,
                "native0/Loader");
        ParsedClass rewritten = parsed("pkg/CodeApi", result.classBytes());
        MethodNode staticAnswer = method(rewritten, "staticAnswer");
        MethodNode privateAnswer = method(rewritten, "privateAnswer");

        assertTrue(result.diagnostics().isEmpty());
        assertFalse(java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                staticAnswer.instructions.iterator(),
                                java.util.Spliterator.ORDERED),
                        false)
                .anyMatch(VarInsnNode.class::isInstance));
        MethodInsnNode staticHelper = helperCall(staticAnswer);
        assertEquals("()I", staticHelper.desc);
        List<VarInsnNode> privateLoads = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                privateAnswer.instructions.iterator(),
                                java.util.Spliterator.ORDERED),
                        false)
                .filter(VarInsnNode.class::isInstance)
                .map(VarInsnNode.class::cast)
                .toList();
        assertEquals(1, privateLoads.size());
        assertEquals(ALOAD, privateLoads.get(0).getOpcode());
        assertEquals(0, privateLoads.get(0).var);
        assertEquals("(Lpkg/CodeApi;)I", helperCall(privateAnswer).desc);
    }

    private MethodNode method(ParsedClass parsedClass, String name) {
        return parsedClass.classNode().methods.stream()
                .filter(method -> method.name.equals(name))
                .findFirst()
                .orElseThrow();
    }

    private MethodInsnNode helperCall(MethodNode method) {
        return java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                method.instructions.iterator(),
                                java.util.Spliterator.ORDERED),
                        false)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.owner.startsWith("j2ll/generated/i_"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void constructorStubInjectsPrivateStaticNativeBodyHelper() {
        ParsedClass parsedClass = parsed("pkg/Foo", AsmFixtureBuilder.minimalClass("pkg/Foo"));
        MethodRewriteDecision constructor = planner.planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals("<init>"))
                .findFirst()
                .orElseThrow();

        InitializerImplementationPlan initializerPlan = initializerPlan(constructor);
        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(
                parsedClass,
                List.of(constructor),
                Map.of(constructor.method().methodKey(), initializerPlan),
                null);
        ParsedClass rewrittenClass = parsed("pkg/Foo", result.classBytes());
        ParsedMethod rewritten = rewrittenClass.methods().stream()
                .filter(method -> method.name().equals("<init>"))
                .findFirst()
                .orElseThrow();
        ParsedMethod helper = rewrittenClass.methods().stream()
                .filter(method -> method.name().equals(
                        constructor.generatedHelperName().orElseThrow()))
                .findFirst()
                .orElseThrow();

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(1, result.applied().size());
        assertFalse(rewritten.accessFlags().isNative());
        assertTrue(rewritten.hasCode());
        assertTrue(helper.accessFlags().isNative());
        assertFalse(helper.hasCode());
        assertEquals("(Lpkg/Foo;)V", helper.descriptor());
        assertTrue(helper.name().matches("[a-p]{32}"));
    }

    @Test
    void classInitializerStubCallsItsExactHashOnlyNativeCarrier() {
        ParsedClass parsedClass = parsed(
                "pkg/StaticState",
                AsmFixtureBuilder.classWithClassInitializer(
                        "pkg/StaticState"));
        MethodRewriteDecision classInitializer =
                planner.planClass(parsedClass, 0x10203040L).stream()
                        .filter(item -> item.method().name().equals("<clinit>"))
                        .findFirst()
                        .orElseThrow();
        InitializerImplementationPlan initializerPlan =
                initializerPlan(classInitializer);

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(
                parsedClass,
                List.of(classInitializer),
                Map.of(
                        classInitializer.method().methodKey(),
                        initializerPlan),
                null);
        ParsedClass rewritten = parsed("pkg/StaticState", result.classBytes());
        String carrierName =
                classInitializer.generatedHelperName().orElseThrow();
        ParsedMethod carrier = rewritten.methods().stream()
                .filter(method -> method.name().equals(carrierName))
                .findFirst()
                .orElseThrow();
        MethodNode stub = rewritten.classNode().methods.stream()
                .filter(method -> method.name.equals("<clinit>"))
                .findFirst()
                .orElseThrow();
        MethodInsnNode carrierCall = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                stub.instructions.iterator(),
                                java.util.Spliterator.ORDERED),
                        false)
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.name.equals(carrierName))
                .findFirst()
                .orElseThrow();

        assertTrue(result.diagnostics().isEmpty());
        assertTrue(carrierName.matches("[a-p]{32}"));
        assertEquals("pkg/StaticState", carrierCall.owner);
        assertEquals("()V", carrierCall.desc);
        assertTrue(carrier.accessFlags().isNative());
        assertFalse(carrier.hasCode());
    }

    @Test
    void constructorStubPreservesParameterizedSuperPrefixAndOriginalHelperArguments() {
        ParsedClass parsedClass = parsed("pkg/Child", parameterizedSuperConstructor());
        MethodRewriteDecision constructor = planner.planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals("<init>"))
                .findFirst()
                .orElseThrow();
        InitializerImplementationPlan initializerPlan = initializerPlan(constructor);

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(
                parsedClass,
                List.of(constructor),
                Map.of(constructor.method().methodKey(), initializerPlan),
                null);
        MethodNode rewritten = parsed("pkg/Child", result.classBytes()).classNode().methods.stream()
                .filter(method -> method.name.equals("<init>"))
                .findFirst()
                .orElseThrow();
        List<AbstractInsnNode> opcodes = java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(
                                rewritten.instructions.iterator(),
                                java.util.Spliterator.ORDERED),
                        false)
                .filter(instruction -> instruction.getOpcode() >= 0)
                .toList();

        assertTrue(result.diagnostics().isEmpty());
        MethodInsnNode superCall = opcodes.stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == INVOKESPECIAL)
                .findFirst()
                .orElseThrow();
        assertEquals("pkg/Base", superCall.owner);
        assertEquals("(Ljava/lang/String;I)V", superCall.desc);
        assertFalse(opcodes.stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .anyMatch(call -> call.getOpcode() == INVOKESPECIAL
                        && call.owner.equals("java/lang/Object")));

        MethodInsnNode helperCall = opcodes.stream()
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .filter(call -> call.getOpcode() == INVOKESTATIC)
                .findFirst()
                .orElseThrow();
        assertEquals("(Lpkg/Child;Ljava/lang/String;I)V", helperCall.desc);
        int helperIndex = opcodes.indexOf(helperCall);
        assertEquals(ALOAD, ((VarInsnNode) opcodes.get(helperIndex - 3)).getOpcode());
        assertEquals(0, ((VarInsnNode) opcodes.get(helperIndex - 3)).var);
        assertEquals(ALOAD, ((VarInsnNode) opcodes.get(helperIndex - 2)).getOpcode());
        assertEquals(1, ((VarInsnNode) opcodes.get(helperIndex - 2)).var);
        assertEquals(ILOAD, ((VarInsnNode) opcodes.get(helperIndex - 1)).getOpcode());
        assertEquals(2, ((VarInsnNode) opcodes.get(helperIndex - 1)).var);
    }

    private InitializerImplementationPlan initializerPlan(MethodRewriteDecision decision) {
        var cfg = new MethodCfgBuilder().build(decision.method()).artifact().orElseThrow();
        var ir = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(cfg)
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        return xyz.melodysky.testsupport.TestProtectionMaterials.initializerPlanner()
                .plan(decision, ir)
                .orElseThrow(() -> new AssertionError("initializer plan missing for " + ir));
    }

    private byte[] parameterizedSuperConstructor() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, "pkg/Child", null, "pkg/Base", null);
        writer.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd();
        MethodVisitor constructor = writer.visitMethod(
                ACC_PUBLIC,
                "<init>",
                "(Ljava/lang/String;I)V",
                null,
                null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ALOAD, 1);
        constructor.visitVarInsn(ILOAD, 2);
        constructor.visitMethodInsn(
                INVOKESPECIAL,
                "pkg/Base",
                "<init>",
                "(Ljava/lang/String;I)V",
                false);
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitVarInsn(ILOAD, 2);
        constructor.visitFieldInsn(PUTFIELD, "pkg/Child", "value", "I");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ParsedClass parsed(String internalName, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(internalName + ".class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }
}

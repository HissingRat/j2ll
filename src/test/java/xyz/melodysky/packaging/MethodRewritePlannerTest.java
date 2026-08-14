package xyz.melodysky.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class MethodRewritePlannerTest implements Opcodes {
    private final MethodRewritePlanner planner = new MethodRewritePlanner();

    @Test
    void ordinaryClassMethodUsesNativeOriginal() {
        ParsedClass parsedClass = parsed("pkg/Foo", AsmFixtureBuilder.classWithIntMethod("pkg/Foo", "answer", 1));

        MethodRewriteDecision decision = planner.planClass(parsedClass).stream()
                .filter(item -> item.method().name().equals("answer"))
                .findFirst()
                .orElseThrow();

        assertEquals(MethodRewriteStrategy.NATIVE_ORIGINAL, decision.strategy());
        assertEquals("pkg/Foo", decision.registrationOwner());
    }

    @Test
    void constructorAndClassInitializerUseStubs() {
        ParsedClass constructorClass = parsed("pkg/Foo", AsmFixtureBuilder.minimalClass("pkg/Foo"));
        ParsedClass clinitClass = parsed("pkg/WithClinit", AsmFixtureBuilder.classWithClassInitializer("pkg/WithClinit"));

        assertEquals(MethodRewriteStrategy.CONSTRUCTOR_STUB, planner.planClass(constructorClass).get(0).strategy());
        assertEquals(MethodRewriteStrategy.CLASS_INITIALIZER_STUB, planner.planClass(clinitClass).get(0).strategy());
    }

    @Test
    void interfaceDefaultMethodUsesInterfaceStubAndAbstractIsNotApplicable() {
        ParsedClass parsedClass = parsed("pkg/Api", AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"));

        var decisions = planner.planClass(parsedClass);

        assertEquals(MethodRewriteStrategy.NOT_APPLICABLE, decisions.stream()
                .filter(decision -> decision.method().name().equals("call"))
                .findFirst()
                .orElseThrow()
                .strategy());
        assertEquals("ABSTRACT_OR_NO_CODE", decisions.stream()
                .filter(decision -> decision.method().name().equals("call"))
                .findFirst()
                .orElseThrow()
                .reasonCode());
        assertEquals(MethodRewriteStrategy.INTERFACE_METHOD_STUB, decisions.stream()
                .filter(decision -> decision.method().name().equals("answer"))
                .findFirst()
                .orElseThrow()
                .strategy());
    }

    @Test
    void interfaceDefaultStaticAndPrivateMethodsWithCodeUseStubs() {
        ParsedClass parsedClass = parsed("pkg/CodeApi", interfaceWithCodeMethods("pkg/CodeApi"));

        var decisions = planner.planClass(parsedClass);

        assertEquals(3, decisions.size());
        assertTrue(decisions.stream().allMatch(decision ->
                decision.strategy() == MethodRewriteStrategy.INTERFACE_METHOD_STUB));
        assertTrue(decisions.stream().allMatch(decision ->
                decision.generatedHelperName().orElseThrow().matches("j2ll_m_[0-9a-f]{32}")));
        assertTrue(decisions.stream().allMatch(decision ->
                decision.registrationOwner().matches("j2ll/generated/i_[0-9a-f]{32}")));
    }

    @Test
    void interfaceHelperIdentifiersAreStableWithinBuildAndVaryAcrossBuilds() {
        ParsedClass parsedClass = parsed(
                "sensitive/acme/SecretApi",
                AsmFixtureBuilder.interfaceWithAbstractAndDefault(
                        "sensitive/acme/SecretApi"));

        MethodRewriteDecision first = interfaceDecision(
                planner.planClass(parsedClass, 0x1234L));
        MethodRewriteDecision repeated = interfaceDecision(
                planner.planClass(parsedClass, 0x1234L));
        MethodRewriteDecision distinct = interfaceDecision(
                planner.planClass(parsedClass, 0x5678L));

        assertEquals(first.registrationOwner(), repeated.registrationOwner());
        assertEquals(first.generatedHelperName(), repeated.generatedHelperName());
        assertNotEquals(first.registrationOwner(), distinct.registrationOwner());
        assertNotEquals(first.generatedHelperName(), distinct.generatedHelperName());
        assertTrue(first.registrationOwner().matches("j2ll/generated/i_[0-9a-f]{32}"));
        assertTrue(first.generatedHelperName().orElseThrow().matches("j2ll_m_[0-9a-f]{32}"));
        assertFalse(first.registrationOwner().contains("sensitive"));
        assertFalse(first.registrationOwner().contains("SecretApi"));
        assertFalse(first.generatedHelperName().orElseThrow().contains("answer"));
    }

    @Test
    void nativeMethodIsNotApplicable() {
        ParsedClass parsedClass = parsed("pkg/NativeApi", AsmFixtureBuilder.classWithVoidMethod(
                "pkg/NativeApi",
                "java/lang/Object",
                null,
                ACC_PUBLIC,
                "call",
                ACC_PUBLIC | ACC_NATIVE));

        MethodRewriteDecision decision = planner.planClass(parsedClass).get(0);

        assertEquals(MethodRewriteStrategy.NOT_APPLICABLE, decision.strategy());
        assertEquals("ALREADY_NATIVE", decision.reasonCode());
    }

    @Test
    void registrationPlanSkipsNotApplicableAndUsesHelperNames() {
        ParsedClass parsedClass = parsed("pkg/Api", AsmFixtureBuilder.interfaceWithAbstractAndDefault("pkg/Api"));

        NativeRegistrationPlan plan = new NativeRegistrationPlanner().plan(planner.planClass(parsedClass));

        assertEquals(1, plan.entries().size());
        assertTrue(plan.entries().get(0).methodName().matches("j2ll_m_[0-9a-f]{32}"));
        assertTrue(plan.entries().get(0).registrationOwner().matches(
                "j2ll/generated/i_[0-9a-f]{32}"));
    }

    private MethodRewriteDecision interfaceDecision(
            java.util.List<MethodRewriteDecision> decisions) {
        return decisions.stream()
                .filter(decision -> decision.method().name().equals("answer"))
                .findFirst()
                .orElseThrow();
    }

    private ParsedClass parsed(String internalName, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(internalName + ".class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private byte[] interfaceWithCodeMethods(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V11, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, internalName, null, "java/lang/Object", null);
        methodReturningInt(writer, ACC_PUBLIC, "defaultAnswer");
        methodReturningInt(writer, ACC_PUBLIC | ACC_STATIC, "staticAnswer");
        methodReturningInt(writer, ACC_PRIVATE, "privateAnswer");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void methodReturningInt(ClassWriter writer, int access, String name) {
        MethodVisitor method = writer.visitMethod(access, name, "()I", null, null);
        method.visitCode();
        method.visitInsn(ICONST_1);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}

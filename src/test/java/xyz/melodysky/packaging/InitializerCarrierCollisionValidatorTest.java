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
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlan;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlanner;

class InitializerCarrierCollisionValidatorTest implements Opcodes {
    private static final String OWNER = "pkg/CarrierCollision";

    @Test
    void rejectsSourceMethodWithExactGeneratedCarrierSignature() {
        MethodRewritePlanner planner = new MethodRewritePlanner();
        ParsedClass base = parsed(AsmFixtureBuilder.minimalClass(OWNER));
        String carrierName = constructorDecision(planner, base)
                .generatedHelperName()
                .orElseThrow();
        ParsedClass colliding = parsed(classWithCollision(carrierName));
        MethodRewriteDecision decision = constructorDecision(
                planner,
                colliding);

        var diagnostics = new InitializerCarrierCollisionValidator().validate(
                List.of(colliding),
                List.of(decision));

        assertEquals(carrierName, decision.generatedHelperName().orElseThrow());
        assertEquals(1, diagnostics.size());
        assertEquals(
                PackagingDiagnostics.GENERATED_INITIALIZER_HELPER_COLLISION,
                diagnostics.get(0).code());
    }

    @Test
    void directRewriterFailsClosedWithoutReplacingCollidingSourceMethod() {
        MethodRewritePlanner planner = new MethodRewritePlanner();
        ParsedClass base = parsed(AsmFixtureBuilder.minimalClass(OWNER));
        String carrierName = constructorDecision(planner, base)
                .generatedHelperName()
                .orElseThrow();
        ParsedClass colliding = parsed(classWithCollision(carrierName));
        MethodRewriteDecision decision = constructorDecision(
                planner,
                colliding);
        InitializerImplementationPlan initializerPlan = initializerPlan(
                decision);

        ClassRewriteResult result = new NativeOriginalClassRewriter().rewrite(
                colliding,
                List.of(decision),
                Map.of(decision.method().methodKey(), initializerPlan),
                null);
        ParsedClass rewritten = parsed(result.classBytes());

        assertTrue(result.applied().isEmpty());
        assertEquals(1, result.diagnostics().size());
        assertEquals(
                PackagingDiagnostics.GENERATED_INITIALIZER_HELPER_COLLISION,
                result.diagnostics().get(0).code());
        assertTrue(rewritten.methods().stream()
                .filter(method -> method.name().equals("<init>"))
                .findFirst()
                .orElseThrow()
                .hasCode());
        var sourceCollision = rewritten.methods().stream()
                .filter(method -> method.name().equals(carrierName))
                .findFirst()
                .orElseThrow();
        assertFalse(sourceCollision.accessFlags().isNative());
        assertTrue(sourceCollision.hasCode());
    }

    private MethodRewriteDecision constructorDecision(
            MethodRewritePlanner planner,
            ParsedClass parsedClass) {
        return planner.planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(decision -> decision.method().name().equals("<init>"))
                .findFirst()
                .orElseThrow();
    }

    private InitializerImplementationPlan initializerPlan(
            MethodRewriteDecision decision) {
        var cfg = new MethodCfgBuilder()
                .build(decision.method())
                .artifact()
                .orElseThrow();
        var ir = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(cfg)
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        return xyz.melodysky.testsupport.TestProtectionMaterials.initializerPlanner()
                .plan(decision, ir)
                .orElseThrow();
    }

    private ParsedClass parsed(byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(OWNER + ".class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private byte[] classWithCollision(String carrierName) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                OWNER,
                null,
                "java/lang/Object",
                null);
        MethodVisitor constructor = writer.visitMethod(
                ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(
                INVOKESPECIAL,
                "java/lang/Object",
                "<init>",
                "()V",
                false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        MethodVisitor collision = writer.visitMethod(
                ACC_PRIVATE | ACC_STATIC,
                carrierName,
                "(L" + OWNER + ";)V",
                null,
                null);
        collision.visitCode();
        collision.visitInsn(RETURN);
        collision.visitMaxs(0, 0);
        collision.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

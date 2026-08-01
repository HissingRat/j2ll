package xyz.melodysky.analysis.method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.implementation;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.method;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.program;
import static xyz.melodysky.analysis.method.NativeMethodInternalizationTestFixtures.type;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.pipeline.MethodInternalizationAnalysisWorldBuilder;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;

class PublicMethodInternalizationCombinedWorldTest implements Opcodes {
    private static final String TARGET_OWNER = "fixture/PublicTarget";
    private static final String TARGET_KEY = TARGET_OWNER + "#target!()I";
    private static final String CALLER_OWNER = "fixture/InternalCaller";
    private static final String CALLER_KEY = CALLER_OWNER + "#caller!()I";

    @TempDir
    Path temp;

    @Test
    void configuredClasspathCallerKeepsAllowlistedPublicStaticMethod()
            throws Exception {
        var input = program(
                type(
                        TARGET_OWNER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(ACC_PUBLIC | ACC_STATIC, "target", "()I")),
                type(
                        CALLER_OWNER,
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PUBLIC | ACC_STATIC,
                                "caller",
                                "()I",
                                visitor -> {
                                    visitor.visitMethodInsn(
                                            INVOKESTATIC,
                                            TARGET_OWNER,
                                            "target",
                                            "()I",
                                            false);
                                    visitor.visitInsn(IRETURN);
                                })));
        Path classpathJar = externalCallerJar();
        var worldResult = new MethodInternalizationAnalysisWorldBuilder()
                .build(input, List.of(classpathJar));
        assertTrue(worldResult.complete(), worldResult.diagnostics().toString());
        var world = worldResult.world().orElseThrow();

        NativeImplementationPlan implementations =
                new NativeImplementationPlan(List.of(
                        implementation(
                                method(input, TARGET_KEY),
                                NativeImplementationPath.LLVM_NATIVE_PATH,
                                List.of(),
                                List.of(),
                                List.of()),
                        implementation(
                                method(input, CALLER_KEY),
                                NativeImplementationPath.LLVM_NATIVE_PATH,
                                List.of(),
                                List.of(TARGET_KEY),
                                List.of())));
        NativeMethodInternalizationPlan plan =
                new NativeMethodInternalizationPlanner().plan(
                        true,
                        WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                        world.combinedProgram(),
                        world.hierarchy(),
                        world.callGraph(),
                        world.reflectionPlan(),
                        Set.of(),
                        implementations,
                        Set.of(NativeMethodId.fromMethodKey(TARGET_KEY)));

        NativeMethodInternalizationDecision decision =
                plan.decisionFor(TARGET_KEY).orElseThrow();
        assertFalse(decision.internalized());
        assertTrue(decision.reasons().contains(
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_CALLER_NOT_NATIVE_LOWERED));
        assertTrue(decision.callerMethodKeys().contains(
                "fixture/ExternalCaller#call!()I"));
    }

    private Path externalCallerJar() throws Exception {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                "fixture/ExternalCaller",
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "call",
                "()I",
                null,
                null);
        method.visitCode();
        method.visitMethodInsn(
                INVOKESTATIC,
                TARGET_OWNER,
                "target",
                "()I",
                false);
        method.visitInsn(IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        Path jar = temp.resolve("external-caller.jar");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(jar))) {
            JarEntry entry = new JarEntry("fixture/ExternalCaller.class");
            entry.setTime(0L);
            output.putNextEntry(entry);
            output.write(writer.toByteArray());
            output.closeEntry();
        }
        return jar;
    }
}

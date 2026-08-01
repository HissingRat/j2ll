package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.method.NativeMethodId;
import xyz.melodysky.analysis.method.NativeMethodUseAnalyzer;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ClassParseDiagnostics;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class MethodInternalizationAnalysisWorldBuilderTest implements Opcodes {
    private static final String TARGET = "sample/Target";

    @TempDir
    Path temp;

    @Test
    void combinesClasspathCallsAndMetadataObservers() throws Exception {
        ParsedProgram input = parsedProgram(Map.of(
                TARGET,
                targetClass(),
                "sample/ReflectTarget",
                AsmFixtureBuilder.classWithReflectionTarget(
                        "sample/ReflectTarget")));
        Path classpath = writeJar(
                "observers.jar",
                Map.of(
                        "sample/StaticCaller",
                        staticCallerClass(),
                        "sample/Sub",
                        inheritedVirtualCallerClass(),
                        "sample/HandleObserver",
                        handleObserverClass(),
                        "sample/EnclosingObserver",
                        enclosingObserverClass(),
                        "sample/ReflectCaller",
                        AsmFixtureBuilder.classWithStaticReflectionMethods(
                                "sample/ReflectCaller",
                                "sample/ReflectTarget")));

        MethodInternalizationAnalysisWorldBuilder.Result result =
                new MethodInternalizationAnalysisWorldBuilder().build(
                        input,
                        List.of(classpath));

        assertTrue(result.complete(), result.diagnostics().toString());
        MethodInternalizationAnalysisWorld world =
                result.world().orElseThrow();
        assertTrue(world.combinedProgram()
                .findClass("sample/StaticCaller")
                .isPresent());
        assertTrue(world.hierarchy().lookupClass("sample/Sub").isPresent());

        NativeMethodId staticTarget =
                new NativeMethodId(TARGET, "staticTarget", "()V");
        NativeMethodId virtualTarget =
                new NativeMethodId(TARGET, "virtualTarget", "()V");
        NativeMethodId handleTarget =
                new NativeMethodId(TARGET, "handleTarget", "()V");
        NativeMethodId enclosingTarget =
                new NativeMethodId(TARGET, "enclosingTarget", "()V");
        NativeMethodId reflectionTarget = new NativeMethodId(
                "sample/ReflectTarget",
                "invokeTarget",
                "()Ljava/lang/String;");
        var uses = new NativeMethodUseAnalyzer().analyze(
                world.combinedProgram(),
                world.callGraph(),
                world.reflectionPlan(),
                Set.of(
                        staticTarget,
                        virtualTarget,
                        handleTarget,
                        enclosingTarget,
                        reflectionTarget));

        assertTrue(uses.incomingCalls(staticTarget).stream().anyMatch(use ->
                use.callerMethodKey()
                        .equals("sample/StaticCaller#call!()V")));
        assertTrue(uses.incomingCalls(virtualTarget).stream().anyMatch(use ->
                use.callerMethodKey()
                        .equals("sample/Sub#callVirtual!()V")));
        assertTrue(uses.methodHandleReferences().contains(handleTarget));
        assertTrue(uses.reflectionObservers().contains(reflectionTarget));
        assertTrue(uses.enclosingMethodReferences().contains(enclosingTarget));
    }

    @Test
    void duplicateInputAndClasspathClassFailsClosed() throws Exception {
        ParsedProgram input = parsedProgram(Map.of(TARGET, targetClass()));
        Path classpath = writeJar(
                "duplicate.jar",
                Map.of(TARGET, targetClass()));

        MethodInternalizationAnalysisWorldBuilder.Result result =
                new MethodInternalizationAnalysisWorldBuilder().build(
                        input,
                        List.of(classpath));

        assertFalse(result.complete());
        assertTrue(result.world().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(
                        ClassParseDiagnostics.DUPLICATE_CLASS)));
    }

    @Test
    void unreadableClasspathArtifactFailsClosed() throws Exception {
        ParsedProgram input = parsedProgram(Map.of(TARGET, targetClass()));
        Path invalidJar = temp.resolve("invalid.jar");
        Files.writeString(invalidJar, "not a jar");

        MethodInternalizationAnalysisWorldBuilder.Result result =
                new MethodInternalizationAnalysisWorldBuilder().build(
                        input,
                        List.of(invalidJar));

        assertFalse(result.complete());
        assertTrue(result.world().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(
                        ClassParseDiagnostics.CLASS_SOURCE_READ_FAILED)));
    }

    private ParsedProgram parsedProgram(Map<String, byte[]> classes) {
        AsmClassParser parser = new AsmClassParser();
        ArrayList<ParsedClass> parsed = new ArrayList<>();
        classes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parsed.add(parser.parse(
                                new ClassFileEntry(
                                        entry.getKey() + ".class",
                                        entry.getValue(),
                                        "input"))
                        .artifact()
                        .orElseThrow()));
        return new ParsedProgram(parsed);
    }

    private Path writeJar(
            String name,
            Map<String, byte[]> classes) throws Exception {
        Path jar = temp.resolve(name);
        try (JarOutputStream output =
                new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                JarEntry jarEntry = new JarEntry(entry.getKey() + ".class");
                jarEntry.setTime(0L);
                output.putNextEntry(jarEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private byte[] targetClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        emitConstructor(writer, TARGET, "java/lang/Object");
        emitVoidMethod(
                writer,
                ACC_PROTECTED | ACC_STATIC,
                "staticTarget");
        emitVoidMethod(
                writer,
                ACC_PROTECTED,
                "virtualTarget");
        emitVoidMethod(
                writer,
                ACC_PROTECTED | ACC_STATIC,
                "handleTarget");
        emitVoidMethod(
                writer,
                ACC_PROTECTED | ACC_STATIC,
                "enclosingTarget");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] staticCallerClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC,
                "sample/StaticCaller",
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "call",
                "()V",
                null,
                null);
        method.visitCode();
        method.visitMethodInsn(
                INVOKESTATIC,
                TARGET,
                "staticTarget",
                "()V",
                false);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] inheritedVirtualCallerClass() {
        String owner = "sample/Sub";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, owner, null, TARGET, null);
        emitConstructor(writer, owner, TARGET);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC,
                "callVirtual",
                "()V",
                null,
                null);
        method.visitCode();
        method.visitVarInsn(ALOAD, 0);
        method.visitMethodInsn(
                INVOKEVIRTUAL,
                owner,
                "virtualTarget",
                "()V",
                false);
        method.visitInsn(RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] handleObserverClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC,
                "sample/HandleObserver",
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                "observe",
                "()V",
                null,
                null);
        method.visitCode();
        method.visitLdcInsn(new Handle(
                H_INVOKESTATIC,
                TARGET,
                "handleTarget",
                "()V",
                false));
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] enclosingObserverClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
                V17,
                ACC_PUBLIC,
                "sample/EnclosingObserver",
                null,
                "java/lang/Object",
                null);
        writer.visitOuterClass(TARGET, "enclosingTarget", "()V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private void emitConstructor(
            ClassWriter writer,
            String owner,
            String superName) {
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
                superName,
                "<init>",
                "()V",
                false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
    }

    private void emitVoidMethod(
            ClassWriter writer,
            int access,
            String name) {
        MethodVisitor method = writer.visitMethod(
                access,
                name,
                "()V",
                null,
                null);
        method.visitCode();
        method.visitInsn(RETURN);
        method.visitMaxs(0, (access & ACC_STATIC) == 0 ? 1 : 0);
        method.visitEnd();
    }
}

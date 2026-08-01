package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.world.WholeProgramAnalysisFeature;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.config.BinaryProtectionConfig;
import xyz.melodysky.config.IntermediatesConfig;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.config.LlvmProtectionConfig;
import xyz.melodysky.config.ProtectionConfig;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.config.TargetConfig;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeMethodImplementation;
import xyz.melodysky.toolchain.TargetTriple;

class FieldInternalizationPipelineTest implements Opcodes {
    private static final String OWNER = "pkg/State";
    private static final String FIELD_KEY = OWNER + "#state!I";

    @TempDir
    Path temp;

    @Test
    void currentJarApprovalInternalizesARealCandidateWithoutReadingClasspath() throws Exception {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        OWNER + ".class",
                        candidateClass(),
                        "fixture"))
                .artifact()
                .orElseThrow();
        ParsedMethod parsedMethod = parsedClass.methods().stream()
                .filter(method -> method.name().equals("read"))
                .findFirst()
                .orElseThrow();
        ParsedProgram program = new ParsedProgram(List.of(parsedClass));
        IrValue resultValue = new IrValue("%state", IrType.I32);
        IrMethod irMethod = new IrMethod(
                OWNER,
                "read",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.fieldGet(
                                resultValue,
                                IrOpcode.GET_STATIC,
                                List.of(),
                                FIELD_KEY)),
                        IrTerminator.returnValue(resultValue))));

        Path inputJar = temp.resolve("input.jar");
        try (JarOutputStream ignored =
                new JarOutputStream(Files.newOutputStream(inputJar))) {
            // A valid empty JAR is enough for multi-release entry discovery.
        }
        ResolvedConfig config = config(
                inputJar,
                temp.resolve("missing-dependency.jar"));
        FieldInternalizationPipelineResult result =
                new FieldInternalizationPipeline().run(
                        config,
                        program,
                        Map.of(irMethod.methodKey(), irMethod),
                        implementationPlan(parsedMethod),
                        17L,
                        WholeProgramAnalysisPolicy.currentJarOnly(
                                List.of(WholeProgramAnalysisFeature.FIELD_INTERNALIZATION)));

        assertEquals(
                WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED,
                result.analysisScope());
        assertFalse(result.classPathAnalyzed());
        assertEquals(1, result.plan().internalizedFields().size());
        assertEquals(
                IrOpcode.GET_NATIVE_STATIC,
                result.methods()
                        .get(irMethod.methodKey())
                        .blocks()
                        .get(0)
                        .instructions()
                        .get(0)
                        .opcode());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().value().equals(
                        "WHOLE_PROGRAM_CURRENT_JAR_ONLY_USER_APPROVED")));
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.message().contains("missing-dependency.jar")));
    }

    @Test
    void finalLlvmPathAllowsSameOwnerInstanceAccessor() throws Exception {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        OWNER + ".class",
                        candidateClass(ACC_PRIVATE),
                        "fixture"))
                .artifact()
                .orElseThrow();
        ParsedMethod parsedMethod = parsedClass.methods().stream()
                .filter(method -> method.name().equals("read"))
                .findFirst()
                .orElseThrow();
        ParsedProgram program = new ParsedProgram(List.of(parsedClass));
        IrValue resultValue = new IrValue("%state", IrType.I32);
        IrMethod irMethod = new IrMethod(
                OWNER,
                "read",
                "()I",
                IrType.I32,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(IrInstruction.fieldGet(
                                resultValue,
                                IrOpcode.GET_STATIC,
                                List.of(),
                                FIELD_KEY)),
                        IrTerminator.returnValue(resultValue))));

        Path inputJar = temp.resolve("instance-input.jar");
        try (JarOutputStream ignored =
                new JarOutputStream(Files.newOutputStream(inputJar))) {
            // A valid empty JAR is enough for multi-release entry discovery.
        }
        FieldInternalizationPipelineResult result =
                new FieldInternalizationPipeline().run(
                        config(inputJar, temp.resolve("missing-instance-dependency.jar")),
                        program,
                        Map.of(irMethod.methodKey(), irMethod),
                        implementationPlan(parsedMethod),
                        23L,
                        WholeProgramAnalysisPolicy.currentJarOnly(
                                List.of(WholeProgramAnalysisFeature.FIELD_INTERNALIZATION)));

        assertFalse(parsedMethod.accessFlags().isStatic());
        assertEquals(1, result.plan().internalizedFields().size());
        assertFalse(result.plan()
                .internalizedFields()
                .get(0)
                .accesses()
                .get(0)
                .methodStatic());
        assertEquals(
                IrOpcode.GET_NATIVE_STATIC,
                result.methods()
                        .get(irMethod.methodKey())
                        .blocks()
                        .get(0)
                        .instructions()
                        .get(0)
                        .opcode());
    }

    private ResolvedConfig config(Path inputJar, Path missingClasspath) {
        TargetConfig target = TargetConfig.single(TargetTriple.LINUX_X64);
        return new ResolvedConfig(
                1,
                inputJar,
                List.of(missingClasspath),
                null,
                null,
                AnalysisWorld.PARTIAL_WORLD,
                temp.resolve("out"),
                List.of(),
                List.of(),
                target,
                target.enabledTargets(),
                "native0",
                SignaturePolicy.FAIL,
                null,
                new IntermediatesConfig(false, false, false, false, false),
                new ProtectionConfig(
                        true,
                        "seed",
                        new IrProtectionConfig(
                                true,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                true,
                                false,
                                false),
                        new LlvmProtectionConfig(
                                false,
                                false,
                                false,
                                false,
                                false,
                                false),
                        new BinaryProtectionConfig(
                                false,
                                false,
                                false,
                                false,
                                false)));
    }

    private NativeImplementationPlan implementationPlan(ParsedMethod method) {
        MethodRewriteDecision decision = new MethodRewriteDecision(
                method,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                OWNER,
                Optional.empty(),
                "TEST");
        NativeRegistrationEntry entry = new NativeRegistrationEntry(
                OWNER,
                method.name(),
                method.descriptor(),
                "j2ll_test_read");
        return new NativeImplementationPlan(List.of(new NativeMethodImplementation(
                entry,
                decision,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.of("j2ll_test_impl"),
                "TEST")));
    }

    private byte[] candidateClass() {
        return candidateClass(ACC_PRIVATE | ACC_STATIC);
    }

    private byte[] candidateClass(int methodAccess) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC | ACC_SUPER, OWNER, null, "java/lang/Object", null);
        writer.visitField(ACC_PRIVATE | ACC_STATIC, "state", "I", null, null)
                .visitEnd();
        MethodVisitor method = writer.visitMethod(
                methodAccess,
                "read",
                "()I",
                null,
                null);
        method.visitCode();
        method.visitFieldInsn(GETSTATIC, OWNER, "state", "I");
        method.visitInsn(IRETURN);
        method.visitMaxs(1, (methodAccess & ACC_STATIC) == 0 ? 1 : 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

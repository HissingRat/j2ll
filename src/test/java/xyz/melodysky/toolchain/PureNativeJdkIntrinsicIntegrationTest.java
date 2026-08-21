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
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.pass.JdkPureNativeIntrinsicPass;
import xyz.melodysky.ir.pass.JdkPureNativeIntrinsicPipeline;
import xyz.melodysky.ir.pass.OptimizationPipeline;
import xyz.melodysky.ir.pass.PassContext;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;
import xyz.melodysky.toolchain.localref.NativeLocalReferenceOwnership;
import xyz.melodysky.toolchain.localref.NativeLocalReferencePlanner;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class PureNativeJdkIntrinsicIntegrationTest {
    @Test
    void lowersByteBufferBigEndianIntChainThroughTheProductionNativePath()
            throws Exception {
        ParsedClass parsedClass = parseFixture();
        ParsedMethod parsedMethod = parsedClass.methods().stream()
                .filter(method -> method.name().equals("encode"))
                .findFirst()
                .orElseThrow();
        IrMethod raw = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder()
                        .build(parsedMethod)
                        .artifact()
                        .orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        IrMethod optimized = OptimizationPipeline.defaultPipeline()
                .run(raw, PassContext.empty())
                .artifact()
                .orElseThrow();

        IrMethod intrinsic = new JdkPureNativeIntrinsicPipeline()
                .run(optimized)
                .artifact()
                .orElseThrow();
        List<IrInstruction> instructions = intrinsic.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .toList();
        List<IrInstruction> helperCalls = instructions.stream()
                .filter(instruction -> instruction.opcode()
                        == IrOpcode.CALL_RUNTIME_HELPER)
                .filter(instruction -> instruction.symbol()
                        .map(PureNativeJdkRuntimeHelpers
                                ::isI32BigEndianFrameHelper)
                        .orElse(false))
                .toList();

        assertEquals(
                List.of(
                        PureNativeJdkRuntimeHelpers
                                .I32_BIG_ENDIAN_FRAME_NEW,
                        PureNativeJdkRuntimeHelpers
                                .I32_BIG_ENDIAN_FRAME_WRITE,
                        PureNativeJdkRuntimeHelpers
                                .I32_BIG_ENDIAN_FRAME_FINISH),
                helperCalls.stream()
                        .map(call -> call.symbol().orElseThrow())
                        .toList());
        assertTrue(helperCalls.stream()
                .allMatch(call -> call.exceptionSites().size() == 1));
        assertFalse(instructions.stream()
                .map(IrInstruction::symbol)
                .flatMap(java.util.Optional::stream)
                .anyMatch(symbol -> symbol.startsWith("java/nio/ByteBuffer#")));
        assertFalse(instructions.stream().anyMatch(instruction ->
                instruction.opcode() == IrOpcode.CONST_INT
                        && instruction.intLiteral().orElse(-1) == 4));

        MethodRewriteDecision decision = new MethodRewritePlanner()
                .planClass(parsedClass, 0x6a326c6cL)
                .stream()
                .filter(item -> item.method().methodKey()
                        .equals(parsedMethod.methodKey()))
                .findFirst()
                .orElseThrow();
        NativeImplementationPlan implementationPlan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        new NativeRegistrationPlanner().plan(
                                List.of(decision)),
                        List.of(decision),
                        Map.of(parsedMethod.methodKey(), intrinsic));
        NativeMethodImplementation implementation = implementationPlan
                .implementationFor(parsedMethod.methodKey())
                .orElseThrow();

        assertEquals(
                NativeImplementationPath.LLVM_NATIVE_PATH,
                implementation.path());
        assertEquals("LLVM_JDK_INTRINSIC_HELPER_IR", implementation.reasonCode());
        assertTrue(implementation.passesJniEnv());
        assertTrue(implementation.staticCallKeys().isEmpty());
        assertTrue(implementation.dispatchKeys().isEmpty());

        var localReferences = new NativeLocalReferencePlanner()
                .plan(intrinsic)
                .plan()
                .orElseThrow();
        String allocated = helperCalls.get(0).result().orElseThrow().name();
        String written = helperCalls.get(1).result().orElseThrow().name();
        String finished = helperCalls.get(2).result().orElseThrow().name();
        assertEquals(
                NativeLocalReferenceOwnership.Kind.OWNED,
                localReferences.ownershipByValue().get(allocated).kind());
        assertEquals(
                allocated,
                localReferences.ownershipByValue()
                        .get(written)
                        .aliasSource()
                        .orElseThrow());
        assertEquals(
                written,
                localReferences.ownershipByValue()
                        .get(finished)
                        .aliasSource()
                        .orElseThrow());

        NativeLlvmCompilation compilation = new NativeLlvmCompiler(
                        xyz.melodysky.testsupport.TestProtectionMaterials.llvmLowerer(),
                        new LlvmTextEmitter())
                .compile(
                        implementationPlan,
                        Map.of(parsedMethod.methodKey(), intrinsic),
                        LlvmProtectionConfig.disabled(0L));
        String llvm = compilation.textByOwner().get("pkg/IntFrame");
        assertTrue(llvm.contains("call ptr @"
                + PureNativeJdkRuntimeHelpers.I32_BIG_ENDIAN_FRAME_NEW
                + "(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @"
                + PureNativeJdkRuntimeHelpers.I32_BIG_ENDIAN_FRAME_WRITE
                + "(ptr %j2ll_env"));
        assertTrue(llvm.contains("call ptr @"
                + PureNativeJdkRuntimeHelpers.I32_BIG_ENDIAN_FRAME_FINISH
                + "(ptr %j2ll_env"));

        RuntimeHelperReachabilityPlan reachability =
                RuntimeHelperReachabilityPlan.from(compilation);
        assertFalse(reachability.isConservative());
        assertTrue(reachability.emits(
                HostJniRuntimeSourceFamily.PURE_NATIVE_JDK));
        NativeTextBuildKey buildKey = NativeTextBuildKey.fromUtf8(
                "pure-native-jdk-intrinsic-test");
        String cSource = new HostJniCSourceGenerator().generate(
                implementationPlan,
                RuntimeLoaderPlan.create("native0", 0),
                new MethodTableHidingPlanner().plan(
                        implementationPlan.registrationPlan(),
                        false,
                        0L),
                buildKey,
                buildKey,
                buildKey,
                reachability);
        assertTrue(cSource.contains(
                "(*env)->NewByteArray(env, 4)"));
        assertTrue(cSource.contains(
                "jarray j2ll_rt_i32_be_frame_new(JNIEnv* env)"));
        assertTrue(cSource.contains(
                "jarray j2ll_rt_i32_be_frame_write(JNIEnv* env, jarray frame, int32_t value)"));
        assertTrue(cSource.contains(
                "jarray j2ll_rt_i32_be_frame_finish(JNIEnv* env, jarray frame)"));
        assertTrue(cSource.contains(
                "(*env)->SetByteArrayRegion(env, (jbyteArray)frame, 0, 4, encoded)"));
        assertFalse(cSource.contains("java/nio/ByteBuffer"));
        assertEquals(
                1L,
                java.util.regex.Pattern
                        .compile("\\(\\*env\\)->GetMethodID\\(")
                        .matcher(cSource)
                        .results()
                        .count(),
                "the only GetMethodID use is the Loader defining-class resolver; "
                        + "the ByteBuffer intrinsic must not add JVM method lookup");
    }

    @Test
    void leavesNonExactOrEscapingChainsOnTheJvmBridge() {
        IrMethod raw = rawMethod();
        IrInstruction allocate = raw.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.symbol()
                        .map(JdkPureNativeIntrinsicPass.BYTE_BUFFER_ALLOCATE
                                ::equals)
                        .orElse(false))
                .findFirst()
                .orElseThrow();
        IrMethod escaping = new IrMethod(
                raw.owner(),
                raw.name(),
                raw.descriptor(),
                raw.returnType(),
                raw.parameters(),
                List.of(new xyz.melodysky.ir.model.IrBlock(
                        raw.blocks().get(0).name(),
                        raw.blocks().get(0).parameters(),
                        raw.blocks().get(0).exceptionCatchTypes(),
                        raw.blocks().get(0).exceptionEdges(),
                        appendEscape(raw.blocks().get(0).instructions(), allocate),
                        raw.blocks().get(0).terminator())));

        IrMethod result = new JdkPureNativeIntrinsicPass()
                .run(escaping, PassContext.empty());

        assertTrue(result.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .anyMatch(instruction -> instruction.symbol()
                        .map(JdkPureNativeIntrinsicPass.BYTE_BUFFER_ALLOCATE
                                ::equals)
                        .orElse(false)));
    }

    @Test
    void preservesTypedCatchEvidenceAtEveryFusedCallBoundary() {
        ParsedMethod parsedMethod = parseFixture().methods().stream()
                .filter(method -> method.name().equals("encodeCaught"))
                .findFirst()
                .orElseThrow();
        IrMethod raw = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder()
                        .build(parsedMethod)
                        .artifact()
                        .orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        IrMethod optimized = OptimizationPipeline.defaultPipeline()
                .run(raw, PassContext.empty())
                .artifact()
                .orElseThrow();
        List<IrInstruction> originalCalls = optimized.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.symbol()
                        .map(symbol -> symbol.equals(
                                        JdkPureNativeIntrinsicPass.BYTE_BUFFER_ALLOCATE)
                                || symbol.equals(
                                        JdkPureNativeIntrinsicPass.BYTE_BUFFER_PUT_INT)
                                || symbol.equals(
                                        JdkPureNativeIntrinsicPass.BYTE_BUFFER_ARRAY))
                        .orElse(false))
                .toList();

        IrMethod intrinsic = new JdkPureNativeIntrinsicPass()
                .run(optimized, PassContext.empty());
        List<IrInstruction> helperCalls = intrinsic.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.symbol()
                        .map(PureNativeJdkRuntimeHelpers
                                ::isI32BigEndianFrameHelper)
                        .orElse(false))
                .toList();

        assertEquals(3, originalCalls.size());
        assertEquals(3, helperCalls.size());
        assertTrue(originalCalls.stream().allMatch(call ->
                call.exceptionSites().stream()
                        .flatMap(site -> site.handlers().stream())
                        .anyMatch(edge -> "java/lang/RuntimeException"
                                .equals(edge.catchType()))));
        assertEquals(
                originalCalls.stream()
                        .map(IrInstruction::exceptionSites)
                        .toList(),
                helperCalls.stream()
                        .map(IrInstruction::exceptionSites)
                        .toList());
        assertEquals(optimized.blocks().size(), intrinsic.blocks().size());
        for (int index = 0; index < optimized.blocks().size(); index++) {
            assertEquals(
                    optimized.blocks().get(index).name(),
                    intrinsic.blocks().get(index).name());
            assertEquals(
                    optimized.blocks().get(index).exceptionCatchTypes(),
                    intrinsic.blocks().get(index).exceptionCatchTypes());
            assertEquals(
                    optimized.blocks().get(index).exceptionEdges(),
                    intrinsic.blocks().get(index).exceptionEdges());
        }
    }

    @Test
    void provesAliasLifetimeForAnIntrinsicInsideACfgCycle() {
        ParsedClass parsedClass = parseFixture();
        ParsedMethod parsedMethod = parsedClass.methods().stream()
                .filter(method -> method.name().equals("encodeLoop"))
                .findFirst()
                .orElseThrow();
        IrMethod raw = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder()
                        .build(parsedMethod)
                        .artifact()
                        .orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        IrMethod optimized = OptimizationPipeline.defaultPipeline()
                .run(raw, PassContext.empty())
                .artifact()
                .orElseThrow();
        IrMethod intrinsic = new JdkPureNativeIntrinsicPipeline()
                .run(optimized)
                .artifact()
                .orElseThrow();
        MethodRewriteDecision decision = new MethodRewritePlanner()
                .planClass(parsedClass, 0x6a326c6cL)
                .stream()
                .filter(item -> item.method().methodKey()
                        .equals(parsedMethod.methodKey()))
                .findFirst()
                .orElseThrow();
        var directLocalReferencePlan =
                new NativeLocalReferencePlanner().plan(intrinsic);
        assertTrue(
                directLocalReferencePlan.plan().isPresent(),
                directLocalReferencePlan.failureReason()::toString);

        NativeImplementationPlan implementationPlan =
                xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                        new NativeRegistrationPlanner().plan(
                                List.of(decision)),
                        List.of(decision),
                        Map.of(parsedMethod.methodKey(), intrinsic));

        assertTrue(implementationPlan
                .implementationFor(parsedMethod.methodKey())
                .isPresent(), implementationPlan.unavailableReasonCodes()::toString);
        assertTrue(implementationPlan
                .localReferencePlanFor(parsedMethod.methodKey())
                .orElseThrow()
                .emitsReleases());
        assertTrue(implementationPlan.unavailableReasonCodes().isEmpty());
    }

    private List<IrInstruction> appendEscape(
            List<IrInstruction> instructions,
            IrInstruction allocate) {
        java.util.ArrayList<IrInstruction> result =
                new java.util.ArrayList<>(instructions);
        result.add(result.size() - 1, IrInstruction.call(
                java.util.Optional.empty(),
                IrOpcode.CALL_STATIC,
                List.of(allocate.result().orElseThrow()),
                "pkg/Sink#accept!(Ljava/lang/Object;)V"));
        return List.copyOf(result);
    }

    private IrMethod rawMethod() {
        ParsedMethod method = parseFixture().methods().stream()
                .filter(candidate -> candidate.name().equals("encode"))
                .findFirst()
                .orElseThrow();
        return xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder()
                        .build(method)
                        .artifact()
                        .orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
    }

    private ParsedClass parseFixture() {
        return new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/IntFrame.class",
                        fixtureBytes(),
                        "fixture"))
                .artifact()
                .orElseThrow();
    }

    private byte[] fixtureBytes() {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                "pkg/IntFrame",
                null,
                "java/lang/Object",
                null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "encode",
                "(I)[B",
                null,
                null);
        method.visitCode();
        method.visitInsn(Opcodes.ICONST_4);
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/nio/ByteBuffer",
                "allocate",
                "(I)Ljava/nio/ByteBuffer;",
                false);
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "putInt",
                "(I)Ljava/nio/ByteBuffer;",
                false);
        method.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "array",
                "()[B",
                false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        MethodVisitor caught = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "encodeCaught",
                "(I)[B",
                null,
                null);
        Label caughtStart = new Label();
        Label caughtEnd = new Label();
        Label caughtHandler = new Label();
        caught.visitTryCatchBlock(
                caughtStart,
                caughtEnd,
                caughtHandler,
                "java/lang/RuntimeException");
        caught.visitCode();
        caught.visitLabel(caughtStart);
        caught.visitInsn(Opcodes.ICONST_4);
        caught.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/nio/ByteBuffer",
                "allocate",
                "(I)Ljava/nio/ByteBuffer;",
                false);
        caught.visitVarInsn(Opcodes.ILOAD, 0);
        caught.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "putInt",
                "(I)Ljava/nio/ByteBuffer;",
                false);
        caught.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "array",
                "()[B",
                false);
        caught.visitLabel(caughtEnd);
        caught.visitInsn(Opcodes.ARETURN);
        caught.visitLabel(caughtHandler);
        caught.visitVarInsn(Opcodes.ASTORE, 1);
        caught.visitInsn(Opcodes.ICONST_0);
        caught.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
        caught.visitInsn(Opcodes.ARETURN);
        caught.visitMaxs(0, 0);
        caught.visitEnd();

        MethodVisitor loop = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "encodeLoop",
                "(II)I",
                null,
                null);
        Label start = new Label();
        Label end = new Label();
        loop.visitCode();
        loop.visitInsn(Opcodes.ICONST_0);
        loop.visitVarInsn(Opcodes.ISTORE, 2);
        loop.visitInsn(Opcodes.ICONST_0);
        loop.visitVarInsn(Opcodes.ISTORE, 3);
        loop.visitLabel(start);
        loop.visitVarInsn(Opcodes.ILOAD, 2);
        loop.visitVarInsn(Opcodes.ILOAD, 1);
        loop.visitJumpInsn(Opcodes.IF_ICMPGE, end);
        loop.visitVarInsn(Opcodes.ILOAD, 3);
        loop.visitInsn(Opcodes.ICONST_4);
        loop.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/nio/ByteBuffer",
                "allocate",
                "(I)Ljava/nio/ByteBuffer;",
                false);
        loop.visitVarInsn(Opcodes.ILOAD, 0);
        loop.visitVarInsn(Opcodes.ILOAD, 2);
        loop.visitInsn(Opcodes.IADD);
        loop.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "putInt",
                "(I)Ljava/nio/ByteBuffer;",
                false);
        loop.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "array",
                "()[B",
                false);
        loop.visitInsn(Opcodes.ICONST_0);
        loop.visitInsn(Opcodes.BALOAD);
        loop.visitInsn(Opcodes.IADD);
        loop.visitVarInsn(Opcodes.ISTORE, 3);
        loop.visitIincInsn(2, 1);
        loop.visitJumpInsn(Opcodes.GOTO, start);
        loop.visitLabel(end);
        loop.visitVarInsn(Opcodes.ILOAD, 3);
        loop.visitInsn(Opcodes.IRETURN);
        loop.visitMaxs(0, 0);
        loop.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}

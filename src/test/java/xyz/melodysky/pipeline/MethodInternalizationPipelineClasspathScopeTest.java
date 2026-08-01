package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.analysis.callgraph.CallGraph;
import xyz.melodysky.analysis.callgraph.CallGraphBuilder;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyBuilder;
import xyz.melodysky.analysis.hierarchy.HierarchyDiagnostics;
import xyz.melodysky.analysis.method.NativeMethodId;
import xyz.melodysky.analysis.method.NativeMethodInternalizationDecision;
import xyz.melodysky.analysis.method.NativeMethodInternalizationReason;
import xyz.melodysky.analysis.method.PublicMethodInternalizationDiagnostics;
import xyz.melodysky.analysis.method.PublicMethodInternalizationAllowListDiagnostics;
import xyz.melodysky.analysis.reflection.ReflectionPlan;
import xyz.melodysky.analysis.reflection.ReflectionUnsupportedSite;
import xyz.melodysky.analysis.world.WholeProgramAnalysisFeature;
import xyz.melodysky.analysis.world.WholeProgramAnalysisPolicy;
import xyz.melodysky.analysis.world.WholeProgramAnalysisScope;
import xyz.melodysky.config.BinaryProtectionConfig;
import xyz.melodysky.config.IntermediatesConfig;
import xyz.melodysky.config.IrProtectionConfig;
import xyz.melodysky.config.LlvmProtectionConfig;
import xyz.melodysky.config.ProtectionConfig;
import xyz.melodysky.config.ResolvedConfig;
import xyz.melodysky.config.Selector;
import xyz.melodysky.config.SelectorParser;
import xyz.melodysky.config.SignaturePolicy;
import xyz.melodysky.config.TargetConfig;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ClassParseDiagnostics;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeMethodImplementation;
import xyz.melodysky.toolchain.TargetTriple;

class MethodInternalizationPipelineClasspathScopeTest implements Opcodes {
    @TempDir
    Path temp;

    @Test
    void currentJarOnlyDoesNotReadConfiguredClasspath() {
        ParsedProgram input = new ParsedProgram(List.of());
        Path missingClasspath = temp.resolve("missing-dependency.jar");
        TargetConfig target = TargetConfig.single(TargetTriple.LINUX_X64);
        ResolvedConfig config = new ResolvedConfig(
                1,
                temp.resolve("input.jar"),
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
                new IntermediatesConfig(
                        false,
                        false,
                        false,
                        false,
                        false),
                protection());

        MethodInternalizationPipelineResult result =
                new MethodInternalizationPipeline().run(
                        config,
                        input,
                        new ClassHierarchyBuilder()
                                .build(input, AnalysisWorld.PARTIAL_WORLD)
                                .artifact()
                                .orElseThrow(),
                        new CallGraph(List.of()),
                        new ReflectionPlan(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        Set.of(),
                        new NativeImplementationPlan(List.of()),
                        WholeProgramAnalysisPolicy.currentJarOnly(List.of(
                                WholeProgramAnalysisFeature
                                        .METHOD_INTERNALIZATION)),
                        17L);

        assertEquals(
                WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED,
                result.analysisScope());
        assertFalse(result.classPathAnalyzed());
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code().equals(
                        ClassParseDiagnostics.CLASS_SOURCE_READ_FAILED)
                        || diagnostic.message().contains(
                                missingClasspath.toString())));
    }

    @Test
    void invalidPublicAllowlistTargetFailsBeforeClasspathAnalysis() {
        ParsedProgram input = new ParsedProgram(List.of());
        Path missingClasspath = temp.resolve("missing-dependency.jar");
        TargetConfig target = TargetConfig.single(TargetTriple.LINUX_X64);
        ResolvedConfig config = new ResolvedConfig(
                1,
                temp.resolve("input.jar"),
                List.of(missingClasspath),
                null,
                null,
                AnalysisWorld.CLOSED_WORLD,
                temp.resolve("out"),
                List.of(),
                List.of(),
                target,
                target.enabledTargets(),
                "native0",
                SignaturePolicy.FAIL,
                null,
                new IntermediatesConfig(
                        false,
                        false,
                        false,
                        false,
                        false),
                protection(List.of(new SelectorParser().parse(
                        "fixture/Missing#target!()I"))));

        MethodInternalizationPipelineResult result =
                new MethodInternalizationPipeline().run(
                        config,
                        input,
                        new ClassHierarchyBuilder()
                                .build(input, AnalysisWorld.CLOSED_WORLD)
                                .artifact()
                                .orElseThrow(),
                        new CallGraph(List.of()),
                        new ReflectionPlan(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        Set.of(),
                        new NativeImplementationPlan(List.of()),
                        WholeProgramAnalysisPolicy.strict(),
                        19L);

        assertEquals(
                WholeProgramAnalysisScope.UNAVAILABLE,
                result.analysisScope());
        assertFalse(result.classPathAnalyzed());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(
                        PublicMethodInternalizationAllowListDiagnostics
                                .TARGET_NOT_FOUND)));
        assertTrue(result.diagnostics().stream().noneMatch(diagnostic ->
                diagnostic.code().equals(
                        ClassParseDiagnostics.CLASS_SOURCE_READ_FAILED)));
    }

    @Test
    void currentJarOnlyInternalizesExactPublicStaticDespiteUnrelatedReflectionRisk() {
        String target = "fixture/StaticOwner#target!(I)I";
        String caller = "fixture/StaticOwner#caller!(I)I";
        ParsedProgram input = program(type(
                "fixture/StaticOwner",
                "java/lang/Object",
                ACC_PUBLIC,
                method(ACC_PUBLIC | ACC_STATIC, "target", "(I)I", code -> {
                    code.visitVarInsn(ILOAD, 0);
                    code.visitInsn(ICONST_1);
                    code.visitInsn(IADD);
                    code.visitInsn(IRETURN);
                }),
                method(ACC_PUBLIC | ACC_STATIC, "caller", "(I)I", code -> {
                    code.visitVarInsn(ILOAD, 0);
                    code.visitMethodInsn(
                            INVOKESTATIC,
                            "fixture/StaticOwner",
                            "target",
                            "(I)I",
                            false);
                    code.visitInsn(IRETURN);
                })));
        var hierarchy = new ClassHierarchyBuilder()
                .build(input, AnalysisWorld.PARTIAL_WORLD)
                .artifact()
                .orElseThrow();
        ReflectionPlan reflectionPlan = new ReflectionPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of(new ReflectionUnsupportedSite(
                        "fixture/UnrelatedObserver",
                        "scan",
                        "()V",
                        3,
                        "DYNAMIC_REFLECTION_TARGET",
                        "unrelated reflection target is not statically enumerable")));

        MethodInternalizationPipelineResult result =
                new MethodInternalizationPipeline().run(
                        config(
                                AnalysisWorld.PARTIAL_WORLD,
                                List.of(selector(target))),
                        input,
                        hierarchy,
                        new CallGraphBuilder().buildCha(input, hierarchy),
                        reflectionPlan,
                        Set.of(),
                        plan(
                                implementation(input, target),
                                implementation(
                                        input,
                                        caller,
                                        List.of(),
                                        List.of(target),
                                        List.of())),
                        WholeProgramAnalysisPolicy.currentJarOnly(List.of(
                                WholeProgramAnalysisFeature
                                        .METHOD_INTERNALIZATION)),
                        23L);

        assertTrue(decision(result, target).internalized());
        assertEquals(
                WholeProgramAnalysisScope.CURRENT_JAR_ONLY_USER_APPROVED,
                result.analysisScope());
        assertFalse(result.classPathAnalyzed());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(
                        PublicMethodInternalizationDiagnostics
                                .UNRESOLVED_REFLECTION_RISK_ACCEPTED)));
    }

    @Test
    void declaredClosedWorldInternalizesNonFinalPublicAndProtectedSameOwnerInstances() {
        String publicTarget =
                "fixture/InstanceOwner#publicTarget!(I)I";
        String protectedTarget =
                "fixture/InstanceOwner#protectedTarget!(I)I";
        String publicCaller =
                "fixture/InstanceOwner#callPublic!(I)I";
        String protectedCaller =
                "fixture/InstanceOwner#callProtected!(I)I";
        ParsedProgram input = program(type(
                "fixture/InstanceOwner",
                "java/lang/Object",
                ACC_PUBLIC,
                intIdentityMethod(ACC_PUBLIC, "publicTarget"),
                intIdentityMethod(ACC_PROTECTED, "protectedTarget"),
                virtualCaller(
                        ACC_PUBLIC,
                        "callPublic",
                        "fixture/InstanceOwner",
                        "publicTarget"),
                virtualCaller(
                        ACC_PUBLIC,
                        "callProtected",
                        "fixture/InstanceOwner",
                        "protectedTarget")));

        MethodInternalizationPipelineResult result = runClosedWorld(
                input,
                List.of(selector(publicTarget)),
                plan(
                        implementation(input, publicTarget),
                        implementation(input, protectedTarget),
                        implementation(
                                input,
                                publicCaller,
                                List.of(),
                                List.of(),
                                List.of(publicTarget)),
                        implementation(
                                input,
                                protectedCaller,
                                List.of(),
                                List.of(),
                                List.of(protectedTarget))));

        assertTrue(decision(result, publicTarget).internalized());
        assertTrue(decision(result, protectedTarget).internalized());
    }

    @Test
    void declaredClosedWorldKeepsAmbiguousAndCrossOwnerPublicInstances() {
        String ambiguousTarget =
                "fixture/AmbiguousOwner#target!(I)I";
        String ambiguousCaller =
                "fixture/AmbiguousOwner#caller!(I)I";
        String crossOwnerTarget =
                "fixture/CrossOwnerTarget#target!(I)I";
        String crossOwnerCaller =
                "fixture/ForeignCaller#caller!(Lfixture/CrossOwnerTarget;I)I";
        ParsedProgram input = program(
                type(
                        "fixture/AmbiguousOwner",
                        "java/lang/Object",
                        ACC_PUBLIC,
                        intIdentityMethod(ACC_PUBLIC, "target"),
                        virtualCaller(
                                ACC_PUBLIC,
                                "caller",
                                "fixture/AmbiguousOwner",
                                "target")),
                type(
                        "fixture/AmbiguousSub",
                        "fixture/AmbiguousOwner",
                        ACC_PUBLIC,
                        intIdentityMethod(ACC_PUBLIC, "target")),
                type(
                        "fixture/CrossOwnerTarget",
                        "java/lang/Object",
                        ACC_PUBLIC | ACC_FINAL,
                        intIdentityMethod(ACC_PUBLIC, "target")),
                type(
                        "fixture/ForeignCaller",
                        "java/lang/Object",
                        ACC_PUBLIC,
                        method(
                                ACC_PUBLIC | ACC_STATIC,
                                "caller",
                                "(Lfixture/CrossOwnerTarget;I)I",
                                code -> {
                                    code.visitVarInsn(ALOAD, 0);
                                    code.visitVarInsn(ILOAD, 1);
                                    code.visitMethodInsn(
                                            INVOKEVIRTUAL,
                                            "fixture/CrossOwnerTarget",
                                            "target",
                                            "(I)I",
                                            false);
                                    code.visitInsn(IRETURN);
                                })));

        MethodInternalizationPipelineResult result = runClosedWorld(
                input,
                List.of(
                        selector(ambiguousTarget),
                        selector(crossOwnerTarget)),
                plan(
                        implementation(input, ambiguousTarget),
                        implementation(
                                input,
                                ambiguousCaller,
                                List.of(),
                                List.of(),
                                List.of(ambiguousTarget)),
                        implementation(input, crossOwnerTarget),
                        implementation(
                                input,
                                crossOwnerCaller,
                                List.of(),
                                List.of(),
                                List.of(crossOwnerTarget))));

        assertTrue(decision(result, ambiguousTarget).reasons().contains(
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_TARGET_NOT_EXACT));
        assertTrue(decision(result, crossOwnerTarget).reasons().contains(
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_CROSS_OWNER_INSTANCE_CALL));
    }

    @Test
    void incompleteCombinedHierarchyOnlyBlocksPublicInstanceRemoval() {
        String owner = "fixture/IncompleteOwner";
        String missingInterface = "fixture/MissingApi";
        String instanceTarget = owner + "#instanceTarget!(I)I";
        String staticTarget = owner + "#staticTarget!(I)I";
        String caller = owner + "#caller!(I)I";
        ParsedProgram input = program(typeWithInterfaces(
                owner,
                "java/lang/Object",
                ACC_PUBLIC,
                List.of(missingInterface),
                intIdentityMethod(ACC_PUBLIC, "instanceTarget"),
                method(ACC_PUBLIC | ACC_STATIC, "staticTarget", "(I)I", code -> {
                    code.visitVarInsn(ILOAD, 0);
                    code.visitInsn(ICONST_1);
                    code.visitInsn(IADD);
                    code.visitInsn(IRETURN);
                }),
                method(ACC_PUBLIC, "caller", "(I)I", code -> {
                    code.visitVarInsn(ALOAD, 0);
                    code.visitVarInsn(ILOAD, 1);
                    code.visitMethodInsn(
                            INVOKEVIRTUAL,
                            owner,
                            "instanceTarget",
                            "(I)I",
                            false);
                    code.visitVarInsn(ILOAD, 1);
                    code.visitMethodInsn(
                            INVOKESTATIC,
                            owner,
                            "staticTarget",
                            "(I)I",
                            false);
                    code.visitInsn(IADD);
                    code.visitInsn(IRETURN);
                })));

        MethodInternalizationPipelineResult result = runClosedWorld(
                input,
                List.of(selector(instanceTarget), selector(staticTarget)),
                plan(
                        implementation(input, instanceTarget),
                        implementation(input, staticTarget),
                        implementation(
                                input,
                                caller,
                                List.of(),
                                List.of(staticTarget),
                                List.of(instanceTarget))));

        assertEquals(
                WholeProgramAnalysisScope.DECLARED_CLOSED_WORLD,
                result.analysisScope());
        assertTrue(result.classPathAnalyzed());
        assertTrue(decision(result, instanceTarget).reasons().contains(
                NativeMethodInternalizationReason
                        .METHOD_INTERNALIZATION_PUBLIC_INSTANCE_ANALYSIS_WORLD_INCOMPLETE));
        assertTrue(decision(result, staticTarget).internalized());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals(
                        HierarchyDiagnostics.MISSING_EXTERNAL_CLASS)
                        && missingInterface.equals(
                                diagnostic.location().className())));
    }

    private ProtectionConfig protection() {
        return protection(List.of());
    }

    private ProtectionConfig protection(
            List<Selector> publicAllowlist) {
        return new ProtectionConfig(
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
                        false,
                        true,
                        publicAllowlist,
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
                        false));
    }

    private MethodInternalizationPipelineResult runClosedWorld(
            ParsedProgram input,
            List<Selector> publicAllowlist,
            NativeImplementationPlan implementationPlan) {
        var hierarchy = new ClassHierarchyBuilder()
                .build(input, AnalysisWorld.CLOSED_WORLD)
                .artifact()
                .orElseThrow();
        return new MethodInternalizationPipeline().run(
                config(AnalysisWorld.CLOSED_WORLD, publicAllowlist),
                input,
                hierarchy,
                new CallGraphBuilder().buildCha(input, hierarchy),
                noReflection(),
                Set.of(),
                implementationPlan,
                WholeProgramAnalysisPolicy.strict(),
                29L);
    }

    private ResolvedConfig config(
            AnalysisWorld world,
            List<Selector> publicAllowlist) {
        TargetConfig target = TargetConfig.single(TargetTriple.LINUX_X64);
        return new ResolvedConfig(
                1,
                temp.resolve("input.jar"),
                List.of(),
                null,
                null,
                world,
                temp.resolve("out"),
                List.of(),
                List.of(),
                target,
                target.enabledTargets(),
                "native0",
                SignaturePolicy.FAIL,
                null,
                new IntermediatesConfig(
                        false,
                        false,
                        false,
                        false,
                        false),
                protection(publicAllowlist));
    }

    private Selector selector(String methodKey) {
        return new SelectorParser().parse(methodKey);
    }

    private NativeMethodInternalizationDecision decision(
            MethodInternalizationPipelineResult result,
            String methodKey) {
        NativeMethodId id = NativeMethodId.fromMethodKey(methodKey);
        return result.plan().decisions().stream()
                .filter(candidate -> candidate.method().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private NativeImplementationPlan plan(
            NativeMethodImplementation... implementations) {
        return new NativeImplementationPlan(List.of(implementations));
    }

    private NativeMethodImplementation implementation(
            ParsedProgram program,
            String methodKey) {
        return implementation(
                program,
                methodKey,
                List.of(),
                List.of(),
                List.of());
    }

    private NativeMethodImplementation implementation(
            ParsedProgram program,
            String methodKey,
            List<String> directCallTargets,
            List<String> staticCallKeys,
            List<String> dispatchKeys) {
        ParsedMethod method = method(program, methodKey);
        String symbol = "j2ll_test_"
                + Integer.toUnsignedString(methodKey.hashCode(), 16);
        return new NativeMethodImplementation(
                new NativeRegistrationEntry(
                        method.owner(),
                        method.name(),
                        method.descriptor(),
                        symbol),
                new MethodRewriteDecision(
                        method,
                        MethodRewriteStrategy.NATIVE_ORIGINAL,
                        method.owner(),
                        Optional.empty(),
                        "TEST_NATIVE_LOWERED"),
                NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.of(symbol + "_llvm"),
                "TEST_FINAL_PATH",
                false,
                false,
                List.of(),
                directCallTargets,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                staticCallKeys,
                dispatchKeys,
                List.of(),
                Optional.empty());
    }

    private ParsedMethod method(
            ParsedProgram program,
            String methodKey) {
        NativeMethodId id = NativeMethodId.fromMethodKey(methodKey);
        return program.findClass(id.owner())
                .orElseThrow()
                .methods()
                .stream()
                .filter(method -> method.name().equals(id.name())
                        && method.descriptor().equals(id.descriptor()))
                .findFirst()
                .orElseThrow();
    }

    private ParsedProgram program(ClassSpec... classes) {
        ArrayList<ClassSpec> all = new ArrayList<>();
        all.add(type(
                "java/lang/Object",
                null,
                ACC_PUBLIC));
        all.addAll(List.of(classes));
        AsmClassParser parser = new AsmClassParser();
        return new ParsedProgram(all.stream()
                .map(type -> parser.parse(new ClassFileEntry(
                                type.name() + ".class",
                                classBytes(type),
                                "method-internalization-pipeline-test"))
                        .artifact()
                        .orElseThrow())
                .toList());
    }

    private byte[] classBytes(ClassSpec type) {
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES
                        | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                type.access() | ACC_SUPER,
                type.name(),
                null,
                type.superName(),
                type.interfaces().toArray(String[]::new));
        for (MethodSpec definition : type.methods()) {
            MethodVisitor method = writer.visitMethod(
                    definition.access(),
                    definition.name(),
                    definition.descriptor(),
                    null,
                    null);
            method.visitCode();
            definition.body().accept(method);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ClassSpec type(
            String name,
            String superName,
            int access,
            MethodSpec... methods) {
        return typeWithInterfaces(
                name,
                superName,
                access,
                List.of(),
                methods);
    }

    private ClassSpec typeWithInterfaces(
            String name,
            String superName,
            int access,
            List<String> interfaces,
            MethodSpec... methods) {
        return new ClassSpec(
                name,
                superName,
                access,
                List.copyOf(interfaces),
                List.of(methods));
    }

    private MethodSpec method(
            int access,
            String name,
            String descriptor,
            Consumer<MethodVisitor> body) {
        return new MethodSpec(access, name, descriptor, body);
    }

    private MethodSpec intIdentityMethod(
            int access,
            String name) {
        return method(access, name, "(I)I", code -> {
            code.visitVarInsn(ILOAD, 1);
            code.visitInsn(IRETURN);
        });
    }

    private MethodSpec virtualCaller(
            int access,
            String name,
            String owner,
            String target) {
        return method(access, name, "(I)I", code -> {
            code.visitVarInsn(ALOAD, 0);
            code.visitVarInsn(ILOAD, 1);
            code.visitMethodInsn(
                    INVOKEVIRTUAL,
                    owner,
                    target,
                    "(I)I",
                    false);
            code.visitInsn(IRETURN);
        });
    }

    private ReflectionPlan noReflection() {
        return new ReflectionPlan(
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private record ClassSpec(
            String name,
            String superName,
            int access,
            List<String> interfaces,
            List<MethodSpec> methods) {}

    private record MethodSpec(
            int access,
            String name,
            String descriptor,
            Consumer<MethodVisitor> body) {}
}

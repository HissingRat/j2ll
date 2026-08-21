package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.BIPUSH;
import static org.objectweb.asm.Opcodes.IADD;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.IRETURN;
import static org.objectweb.asm.Opcodes.V17;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.MethodTableHidingEntry;
import xyz.melodysky.packaging.MethodTableHidingOwnerPlan;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.testsupport.FakeManagedZig;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.symbols.NativeBinaryPrivacyInspector;
import xyz.melodysky.toolchain.symbols.SymbolVisibilityPlanner;

final class MethodTableHidingNativeIntegrationTest {
    private static final String OWNER_ALPHA = "sensitive/mth/OwnerAlpha_91e8b5fd67a249f4";
    private static final String OWNER_BETA = "sensitive/mth/OwnerBeta_73c4f5e0a8614bbc";
    private static final String METHOD_ALPHA = "alphaMethod_4e902d3bca5f41a6";
    private static final String METHOD_BETA = "betaMethod_85a60137d42b4c9e";
    private static final String METHOD_GAMMA = "gammaMethod_2c8e391fa7406bd5";
    private static final long SEED = 0x4d54485f494e5447L;
    private static final Pattern EXPORTED_C_ROOT = Pattern.compile(
            "JNIEXPORT\\s+jint\\s+JNICALL\\s+([A-Za-z_][A-Za-z0-9_]*)"
                    + "\\s*\\([^;{}]*\\)\\s*\\{");

    @TempDir
    Path temp;

    @Test
    void generatorUsesExternalTransientLayoutPlanAndRejectsMismatches() {
        Fixture fixture = fixture();
        MethodTableHidingPlan hidingPlan = hidingPlan(fixture, SEED);

        String source = new HostJniCSourceGenerator().generate(
                fixture.implementationPlan(),
                fixture.runtimeLoaderPlan(),
                hidingPlan,
                xyz.melodysky.testsupport.TestProtectionMaterials
                        .nativeTextBuildKey());

        assertGeneratedSourceMatchesPlan(source, fixture, hidingPlan);

        NativeRegistrationPlan incomplete = new NativeRegistrationPlan(
                fixture.registrationPlan().entries().subList(
                        0,
                        fixture.registrationPlan().entries().size() - 1));
        MethodTableHidingPlan mismatched =
                new MethodTableHidingPlanner().plan(incomplete, true, SEED);
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniCSourceGenerator().generate(
                        fixture.implementationPlan(),
                        fixture.runtimeLoaderPlan(),
                        mismatched,
                        xyz.melodysky.testsupport.TestProtectionMaterials
                                .nativeTextBuildKey()));
        assertTrue(mismatch.getMessage().contains("does not match"));

        MethodTableHidingPlan enabledButEmpty =
                new MethodTableHidingPlan(true, "mth_" + "0".repeat(32), List.of());
        IllegalArgumentException emptyMismatch = assertThrows(
                IllegalArgumentException.class,
                () -> new HostJniCSourceGenerator().generate(
                        fixture.implementationPlan(),
                        fixture.runtimeLoaderPlan(),
                        enabledButEmpty,
                        xyz.melodysky.testsupport.TestProtectionMaterials
                                .nativeTextBuildKey()));
        assertTrue(emptyMismatch.getMessage().contains("does not cover"));
    }

    @Test
    void generatorUsesOneExplicitBuildKeyForRegistrationText() {
        Fixture fixture = fixture();
        MethodTableHidingPlan hidingPlan = hidingPlan(fixture, SEED);
        HostJniCSourceGenerator generator = new HostJniCSourceGenerator();

        String first = generator.generate(
                fixture.implementationPlan(),
                fixture.runtimeLoaderPlan(),
                hidingPlan,
                NativeTextBuildKey.fromUtf8("jni-build-one"));
        String second = generator.generate(
                fixture.implementationPlan(),
                fixture.runtimeLoaderPlan(),
                hidingPlan,
                NativeTextBuildKey.fromUtf8("jni-build-two"));

        assertNotEquals(first, second);
        assertGeneratedSourceMatchesPlan(first, fixture, hidingPlan);
        assertGeneratedSourceMatchesPlan(second, fixture, hidingPlan);
    }

    @Test
    void builderWritesTheExactExternallySuppliedPlanIntoTheWrapper() throws Exception {
        Fixture fixture = fixture();
        MethodTableHidingPlan firstPlan = hidingPlan(fixture, SEED);
        MethodTableHidingPlan secondPlan = reverseOneOwnerRegistrationOrder(firstPlan);
        assertNotEquals(firstPlan, secondPlan);

        ZigNativeLibraryBuilder builder = new ZigNativeLibraryBuilder();
        ZigBuildWorkspace firstWorkspace = ZigBuildWorkspace.under(temp.resolve("first"));
        ZigBuildWorkspace secondWorkspace = ZigBuildWorkspace.under(temp.resolve("second"));
        Path firstWrapper = builder.writeJniWrapper(
                firstWorkspace,
                "j2ll_mth_first",
                fixture.runtimeLoaderPlan(),
                fixture.implementationPlan(),
                firstPlan);
        Path secondWrapper = builder.writeJniWrapper(
                secondWorkspace,
                "j2ll_mth_second",
                fixture.runtimeLoaderPlan(),
                fixture.implementationPlan(),
                secondPlan);
        String firstSource = Files.readString(firstWrapper);
        String secondSource = Files.readString(secondWrapper);

        assertGeneratedSourceMatchesPlan(firstSource, fixture, firstPlan);
        assertGeneratedSourceMatchesPlan(secondSource, fixture, secondPlan);
        assertNotEquals(firstSource, secondSource);
        assertNotEquals(
                functionAssignmentOrder(firstSource),
                functionAssignmentOrder(secondSource));
    }

    private MethodTableHidingPlan reverseOneOwnerRegistrationOrder(
            MethodTableHidingPlan sourcePlan) {
        boolean[] reversed = {false};
        List<MethodTableHidingOwnerPlan> owners = sourcePlan.owners().stream()
                .map(owner -> {
                    if (reversed[0] || owner.registrationOrder().size() < 2) {
                        return owner;
                    }
                    ArrayList<MethodTableHidingEntry> registrationOrder =
                            new ArrayList<>(owner.registrationOrder());
                    java.util.Collections.reverse(registrationOrder);
                    reversed[0] = true;
                    return new MethodTableHidingOwnerPlan(
                            owner.registrationOwner(),
                            registrationOrder);
                })
                .toList();
        assertTrue(reversed[0], "fixture must include an owner with multiple registrations");
        return new MethodTableHidingPlan(
                true,
                sourcePlan.planId() + "_reordered",
                owners);
    }

    @Test
    void generatedTransientLayoutWrapperCompilesAsHostC() throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for the generated JNI C compile smoke");
        assumeTrue(
                Files.isRegularFile(Path.of(System.getProperty("java.home")).resolve("include/jni.h")),
                "JDK JNI headers are required for the generated JNI C compile smoke");
        Fixture fixture = fixture();
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp.resolve("clang-smoke"));
        Path wrapper = new ZigNativeLibraryBuilder().writeJniWrapper(
                workspace,
                "j2ll_mth_compile",
                fixture.runtimeLoaderPlan(),
                fixture.implementationPlan(),
                hidingPlan(fixture, SEED));
        assertWrapperCompiles(clang, workspace, wrapper, "j2ll_mth_compile");
    }

    @Test
    void generatedOrdinaryTableWrapperIsEncodedAndCompilesAsHostC() throws Exception {
        Path clang = findClang().orElse(null);
        assumeTrue(clang != null, "clang is required for the generated JNI C compile smoke");
        assumeTrue(
                Files.isRegularFile(Path.of(System.getProperty("java.home")).resolve("include/jni.h")),
                "JDK JNI headers are required for the generated JNI C compile smoke");
        Fixture fixture = fixture();
        ZigBuildWorkspace workspace = ZigBuildWorkspace.under(temp.resolve("ordinary-clang-smoke"));
        MethodTableHidingPlan ordinaryPlan = new MethodTableHidingPlanner().plan(
                fixture.registrationPlan(),
                false,
                SEED);
        Path wrapper = new ZigNativeLibraryBuilder().writeJniWrapper(
                workspace,
                "j2ll_ordinary_compile",
                fixture.runtimeLoaderPlan(),
                fixture.implementationPlan(),
                ordinaryPlan);
        String source = Files.readString(wrapper);
        fixture.registrationPlan().entries().forEach(entry -> {
            assertFalse(source.contains(entry.registrationOwner()));
            assertFalse(source.contains(entry.methodName()));
            assertFalse(source.contains(entry.descriptor()));
        });
        assertFalse(source.contains("static JNINativeMethod j2ll_natives_"));
        assertTrue(source.contains("j2ll_native_text_zero(text_scratch, UINT64_C("));
        assertWrapperCompiles(clang, workspace, wrapper, "j2ll_ordinary_compile");
    }

    private void assertWrapperCompiles(
            Path clang,
            ZigBuildWorkspace workspace,
            Path wrapper,
            String objectStem) throws Exception {
        Path include = new ZigJniHeaderSet().prepare(workspace).get(0);
        Path object = workspace.jniDirectory().resolve(objectStem + ".o");

        Process process = new ProcessBuilder(
                        clang.toString(),
                        "-std=c11",
                        "-I",
                        include.toString(),
                        "-c",
                        wrapper.toString(),
                        "-o",
                        object.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(45, TimeUnit.SECONDS), "clang compile timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(Files.isRegularFile(object));
        assertTrue(Files.size(object) > 0);
    }

    @Test
    void fakeManagedZigBuildKeepsOnlyLoaderRootsExported() throws Exception {
        HostPlatform host = HostPlatform.detect().orElse(null);
        assumeTrue(
                FakeManagedZig.supportsCurrentHostFixture(host),
                "fake managed Zig shared-library fixture is only available on Linux/macOS");
        Fixture fixture = fixture();
        MethodTableHidingPlan hidingPlan = hidingPlan(fixture, SEED);
        NativeBuildPlan buildPlan =
                new NativeBuildPlanner().plan(temp, "j2ll_mth", List.of(host.target()));

        ZigNativeBuildResult result;
        try (AutoCloseable ignored =
                FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            result = new ZigNativeLibraryBuilder().build(
                            temp,
                            fixture.runtimeLoaderPlan(),
                            buildPlan,
                            fixture.implementationPlan(),
                            fixture.irMethods(),
                            NativeBuildProgressListener.none(),
                            hidingPlan)
                    .orElseThrow();
        }

        NativeLibraryArtifact artifact = result.artifactFor(host.target()).orElseThrow();
        String source = Files.readString(result.wrapperSourcePath());
        assertGeneratedSourceMatchesPlan(source, fixture, hidingPlan);
        assertEquals(
                new SymbolVisibilityPlanner().loaderExports(host.target()).symbols().stream()
                        .map(symbol -> symbol.name())
                        .toList(),
                artifact.exportedSymbols());
        fixture.registrationPlan().entries().forEach(entry ->
                assertFalse(artifact.exportedSymbols().contains(entry.nativeSymbol())));

        byte[] binary = Files.readAllBytes(artifact.libraryPath());
        for (String plaintext : List.of(
                OWNER_ALPHA,
                OWNER_BETA,
                METHOD_ALPHA,
                METHOD_BETA,
                METHOD_GAMMA)) {
            assertFalse(
                    NativeBinaryPrivacyInspector.contains(
                            binary,
                            plaintext.getBytes(StandardCharsets.UTF_8)),
                    "native plaintext: " + plaintext);
        }
    }

    private Fixture fixture() {
        List<ParsedClass> classes = List.of(
                parse(OWNER_ALPHA, alphaClass()),
                parse(OWNER_BETA, betaClass()));
        MethodRewritePlanner rewritePlanner = new MethodRewritePlanner();
        List<MethodRewriteDecision> decisions = classes.stream()
                .flatMap(parsedClass -> rewritePlanner.planClass(parsedClass, 0x6a326c6cL).stream())
                .toList();
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(decisions);
        Map<String, IrMethod> irMethods = new LinkedHashMap<>();
        for (MethodRewriteDecision decision : decisions) {
            IrMethod method = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                    .lower(new MethodCfgBuilder().build(decision.method()).artifact().orElseThrow())
                    .artifact()
                    .orElseThrow()
                    .irMethod()
                    .orElseThrow();
            irMethods.put(decision.method().methodKey(), method);
        }
        NativeImplementationPlan implementationPlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner().plan(
                registrationPlan,
                decisions,
                irMethods);
        assertEquals(3, implementationPlan.implementations().size());
        assertTrue(implementationPlan.implementations().stream()
                .allMatch(implementation ->
                        implementation.path() == NativeImplementationPath.LLVM_NATIVE_PATH));
        return new Fixture(
                implementationPlan,
                implementationPlan.registrationPlan(),
                Map.copyOf(irMethods),
                RuntimeLoaderPlan.create("native0"));
    }

    private MethodTableHidingPlan hidingPlan(Fixture fixture, long seed) {
        return new MethodTableHidingPlanner().plan(
                fixture.registrationPlan(),
                true,
                seed);
    }

    private void assertGeneratedSourceMatchesPlan(
            String source,
            Fixture fixture,
            MethodTableHidingPlan plan) {
        assertEquals(
                0,
                occurrences(source, "static const uint64_t j2ll_hmt_"));
        assertEquals(
                0,
                occurrences(source, "static const j2ll_hidden_method_function j2ll_hmf_"));
        assertFalse(source.contains("j2ll_hidden_method_function"));
        assertFalse(source.contains("masked_token"));
        assertFalse(source.contains("metadata_index"));
        assertFalse(source.contains("function_index"));
        assertFalse(source.contains("join_scratch"));
        assertFalse(source.contains("static JNINativeMethod j2ll_natives_"));
        for (var owner : plan.owners()) {
            for (MethodTableHidingEntry entry : owner.registrationOrder()) {
                assertFalse(source.contains(
                        "UINT64_C(0x" + hex(entry.token()) + ")"));
            }
        }
        assertEquals(
                fixture.registrationPlan().entries().size(),
                functionAssignmentOrder(source).size());
        assertEquals(
                plan.owners().size(),
                occurrences(
                        source,
                        "j2ll_native_text_zero(methods, (size_t)count * sizeof(JNINativeMethod));"));

        for (var entry : fixture.registrationPlan().entries()) {
            assertFalse(source.contains(entry.registrationOwner()));
            assertFalse(source.contains(entry.methodName()));
            assertFalse(source.contains(entry.descriptor()));
            assertTrue(source.contains(entry.nativeSymbol()));
        }
        assertEquals(
                List.of("JNI_OnLoad"),
                exportedRoots(source));
    }

    private List<String> functionAssignmentOrder(String source) {
        ArrayList<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                        "methods\\[[0-9]+\\]\\.fnPtr = \\(void\\*\\)"
                                + "([A-Za-z_][A-Za-z0-9_]*);")
                .matcher(source);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return List.copyOf(result);
    }

    private List<String> exportedRoots(String source) {
        ArrayList<String> roots = new ArrayList<>();
        Matcher matcher = EXPORTED_C_ROOT.matcher(source);
        while (matcher.find()) {
            roots.add(matcher.group(1));
        }
        return List.copyOf(roots);
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private ParsedClass parse(String owner, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(owner + ".class", bytes, "fixture"))
                .artifact()
                .orElseThrow();
    }

    private byte[] alphaClass() {
        ClassWriter writer = classWriter(OWNER_ALPHA);
        MethodVisitor alpha = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                METHOD_ALPHA,
                "(II)I",
                null,
                null);
        alpha.visitCode();
        alpha.visitVarInsn(ILOAD, 0);
        alpha.visitVarInsn(ILOAD, 1);
        alpha.visitInsn(IADD);
        alpha.visitInsn(IRETURN);
        alpha.visitMaxs(0, 0);
        alpha.visitEnd();

        MethodVisitor beta = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                METHOD_BETA,
                "(I)I",
                null,
                null);
        beta.visitCode();
        beta.visitVarInsn(ILOAD, 0);
        beta.visitIntInsn(BIPUSH, 7);
        beta.visitInsn(IADD);
        beta.visitInsn(IRETURN);
        beta.visitMaxs(0, 0);
        beta.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] betaClass() {
        ClassWriter writer = classWriter(OWNER_BETA);
        MethodVisitor gamma = writer.visitMethod(
                ACC_PUBLIC | ACC_STATIC,
                METHOD_GAMMA,
                "()I",
                null,
                null);
        gamma.visitCode();
        gamma.visitIntInsn(BIPUSH, 42);
        gamma.visitInsn(IRETURN);
        gamma.visitMaxs(0, 0);
        gamma.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ClassWriter classWriter(String owner) {
        ClassWriter writer =
                new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_SUPER,
                owner,
                null,
                "java/lang/Object",
                null);
        return writer;
    }

    private Optional<Path> findClang() {
        String configured = System.getProperty("j2ll.test.clang");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Path.of(configured);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<String> names = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? List.of("clang.exe", "clang")
                : List.of("clang");
        for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(directory).resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static String hex(long value) {
        return String.format(java.util.Locale.ROOT, "%016x", value);
    }

    private record Fixture(
            NativeImplementationPlan implementationPlan,
            NativeRegistrationPlan registrationPlan,
            Map<String, IrMethod> irMethods,
            RuntimeLoaderPlan runtimeLoaderPlan) {}
}

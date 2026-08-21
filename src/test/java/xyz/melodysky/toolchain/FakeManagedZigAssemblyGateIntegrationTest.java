package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.FakeManagedZig;

final class FakeManagedZigAssemblyGateIntegrationTest {
    @TempDir
    Path temp;

    @Test
    void fakeBuildLinksItsEvidenceAndProductionGateRejectsPostLinkTampering()
            throws Exception {
        HostPlatform host = HostPlatform.detect().orElse(null);
        assumeTrue(
                FakeManagedZig.supportsCurrentHostFixture(host),
                "fake managed Zig host fixture is not available");
        assumeTrue(
                Files.exists(Path.of(System.getProperty("java.home")).resolve("include/jni.h")),
                "JDK headers are required for fake managed Zig JNI build");
        Fixture fixture = fixture();

        Path accepted = temp.resolve("accepted");
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("home-ok"))) {
            build(accepted, host, fixture);
        }
        Path evidence = accepted.resolve("native/zig-workspace/evidence/optimized-assembly")
                .resolve(host.target().directoryName());
        try (var files = Files.list(evidence)) {
            List<Path> assemblies = files.filter(path -> path.toString().endsWith(".s")).toList();
            assertFalse(assemblies.isEmpty());
            assertTrue(assemblies.stream().allMatch(this::isNonEmpty));
        }

        Path rejected = temp.resolve("rejected");
        try (AutoCloseable ignored = FakeManagedZig.installAndUseTamperingEvidence(
                temp.resolve("home-tampered"))) {
            IOException failure = assertThrows(
                    IOException.class,
                    () -> build(rejected, host, fixture));
            assertTrue(
                    failure.getMessage().contains("optimized assembly audit failed"),
                    failure.getMessage());
        }
    }

    private NativeLibraryArtifact build(
            Path root,
            HostPlatform host,
            Fixture fixture) throws IOException {
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(
                root,
                "native0",
                List.of(host.target()));
        return new ZigNativeLibraryBuilder()
                .build(
                        root,
                        RuntimeLoaderPlan.create("native0"),
                        buildPlan,
                        fixture.implementationPlan(),
                        fixture.irMethods())
                .orElseThrow()
                .artifactFor(host.target())
                .orElseThrow();
    }

    private Fixture fixture() {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Adder.class",
                        AsmFixtureBuilder.classWithAddMethod("pkg/Adder"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        MethodRewriteDecision decision = new MethodRewritePlanner().planClass(parsedClass, 0x6a326c6cL).stream()
                .filter(item -> item.method().name().equals("add"))
                .findFirst()
                .orElseThrow();
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(List.of(decision));
        ParsedMethod add = parsedClass.methods().stream()
                .filter(method -> method.name().equals("add"))
                .findFirst()
                .orElseThrow();
        IrMethod irMethod = xyz.melodysky.testsupport.TestProtectionMaterials.ssaLowerer()
                .lower(new MethodCfgBuilder().build(add).artifact().orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        Map<String, IrMethod> irMethods = Map.of(decision.method().methodKey(), irMethod);
        NativeImplementationPlan implementationPlan = xyz.melodysky.testsupport.TestProtectionMaterials.implementationPlanner()
                .plan(registrationPlan, List.of(decision), irMethods);
        return new Fixture(implementationPlan, irMethods);
    }

    private boolean isNonEmpty(Path path) {
        try {
            return Files.size(path) != 0L;
        } catch (IOException failure) {
            return false;
        }
    }

    private record Fixture(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) {}
}

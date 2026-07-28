package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.testsupport.FakeManagedZig;

class HostNativeLibraryBuilderTest {
    @TempDir
    Path temp;

    @Test
    void buildsHostOnlyJniLibraryForStaticIntAddAndAuditsExports() throws Exception {
        HostPlatform host = HostPlatform.detect().orElse(null);
        assumeTrue(FakeManagedZig.supportsCurrentHostFixture(host), "fake managed Zig host fixture is not available");
        assumeTrue(Files.exists(Path.of(System.getProperty("java.home")).resolve("include/jni.h")),
                "JDK headers are required for host-only JNI build");
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Adder.class",
                        AsmFixtureBuilder.classWithAddMethod("pkg/Adder"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        MethodRewriteDecision decision = new MethodRewritePlanner().planClass(parsedClass).stream()
                .filter(item -> item.method().name().equals("add"))
                .findFirst()
                .orElseThrow();
        NativeRegistrationPlan registrationPlan = new NativeRegistrationPlanner().plan(List.of(decision));
        NativeBuildPlan buildPlan = new NativeBuildPlanner().plan(temp, "j2llhost", List.of(host.target()));
        ParsedMethod add = parsedClass.methods().stream()
                .filter(method -> method.name().equals("add"))
                .findFirst()
                .orElseThrow();
        IrMethod irMethod = new BytecodeToSsaLowerer()
                .lower(new MethodCfgBuilder().build(add).artifact().orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        Map<String, IrMethod> irMethods = Map.of(decision.method().methodKey(), irMethod);
        NativeImplementationPlan implementationPlan = new NativeImplementationPlanner()
                .plan(registrationPlan, List.of(decision), irMethods);

        NativeLibraryArtifact artifact;
        try (AutoCloseable ignored = FakeManagedZig.installAndUse(temp.resolve("j2ll-home"))) {
            artifact = new HostNativeLibraryBuilder()
                    .buildIfHostTargetSelected(temp, "native0", buildPlan, implementationPlan, irMethods)
                    .orElseThrow();
        }

        String source = Files.readString(artifact.sourcePath());
        String llvm = Files.readString(artifact.sourcePath().getParent().getParent()
                .resolve("llvm")
                .resolve("pkg_Adder.ll"));
        assertTrue(Files.exists(temp.resolve("native/zig-workspace/build.zig")));
        assertTrue(Files.exists(temp.resolve("native/zig-workspace/j2ll-build-manifest.json")));
        assertFalse(Files.exists(temp.resolve("native/zig-workspace/fallback")));
        assertFalse(Files.exists(temp.resolve(
                "native/zig-workspace/fallback/j2ll_fallback_blobs.c")));
        assertTrue(Files.exists(artifact.libraryPath()));
        assertTrue(Files.size(artifact.libraryPath()) > 0);
        assertEquals(64, artifact.sha256().length());
        assertEquals("native0/" + host.target().libraryFileName(), artifact.jarPath());
        assertTrue(source.contains("JNIEnv* env"));
        assertTrue(source.contains("RegisterNatives"));
        assertTrue(source.contains("JNI_OnLoad"));
        assertFalse(source.contains("DefineClass"));
        assertFalse(source.contains("defineHiddenFallback"));
        assertFalse(source.contains("fallback blob"));
        assertTrue(source.contains("extern jint " + implementationPlan.implementations().get(0).llvmFunctionSymbol().orElseThrow()));
        assertTrue(source.contains("return "
                + implementationPlan.implementations().get(0).llvmFunctionSymbol().orElseThrow()
                + "("));
        assertFalse(source.contains("jint result = (jint)"
                + implementationPlan.implementations().get(0).llvmFunctionSymbol().orElseThrow()));
        assertFalse(source.contains("j2ll_lab_slot_"));
        assertFalse(source.contains(" volatile j2ll_lab_"));
        assertTrue(source.contains("return result;"));
        assertFalse(source.contains("return arg0 + arg1;"));
        assertFalse(source.contains("j2ll_rt_div_i32"));
        assertFalse(source.contains("j2ll_rt_array_length_i32"));
        assertFalse(source.contains("j2ll_rt_string_length"));
        assertFalse(source.contains("j2ll_var_handle_method_handle"));
        assertFalse(source.contains("j2ll_parameter_array_for_descriptor"));
        assertTrue(llvm.contains("define external hidden i32 @"
                + implementationPlan.implementations().get(0).llvmFunctionSymbol().orElseThrow()));
        assertTrue(llvm.contains("add i32"));
        assertTrue(artifact.exportedSymbols().contains("JNI_OnLoad"), artifact.exportedSymbols().toString());
        assertFalse(artifact.exportedSymbols().contains("j2ll_register"), artifact.exportedSymbols().toString());
        assertFalse(artifact.exportedSymbols().contains(registrationPlan.entries().get(0).nativeSymbol()),
                artifact.exportedSymbols().toString());
        assertFalse(artifact.exportedSymbols().contains(implementationPlan.implementations().get(0).llvmFunctionSymbol().orElseThrow()),
                artifact.exportedSymbols().toString());
    }
}

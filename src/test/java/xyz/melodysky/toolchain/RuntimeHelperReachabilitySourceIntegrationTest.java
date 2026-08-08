package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.protection.LlvmProtectionConfig;
import xyz.melodysky.frontend.cfg.MethodCfgBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.MethodTableHidingPlan;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class RuntimeHelperReachabilitySourceIntegrationTest {
    @Test
    void finalCompilationPrunesUnusedFamiliesWhileDirectApiStaysConservative()
            throws Exception {
        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Adder.class",
                        AsmFixtureBuilder.classWithAddMethod(
                                "pkg/Adder"),
                        "fixture"))
                .artifact()
                .orElseThrow();
        MethodRewriteDecision decision =
                new MethodRewritePlanner().planClass(parsedClass).stream()
                        .filter(item -> item.method().name()
                                .equals("add"))
                        .findFirst()
                        .orElseThrow();
        ParsedMethod parsedMethod = parsedClass.methods().stream()
                .filter(method -> method.name().equals("add"))
                .findFirst()
                .orElseThrow();
        IrMethod irMethod = new BytecodeToSsaLowerer()
                .lower(new MethodCfgBuilder()
                        .build(parsedMethod)
                        .artifact()
                        .orElseThrow())
                .artifact()
                .orElseThrow()
                .irMethod()
                .orElseThrow();
        NativeRegistrationPlan registrationPlan =
                new NativeRegistrationPlanner().plan(
                        List.of(decision));
        NativeImplementationPlan implementationPlan =
                new NativeImplementationPlanner().plan(
                        registrationPlan,
                        List.of(decision),
                        Map.of(
                                decision.method().methodKey(),
                                irMethod));
        NativeLlvmCompilation compilation =
                new NativeLlvmCompiler(
                        new LlvmModuleLowerer(),
                        new LlvmTextEmitter())
                        .compile(
                                implementationPlan,
                                Map.of(
                                        decision.method().methodKey(),
                                        irMethod),
                                LlvmProtectionConfig.disabled(0L));
        RuntimeHelperReachabilityPlan reachability =
                RuntimeHelperReachabilityPlan.from(compilation);
        MethodTableHidingPlan methodTablePlan =
                new MethodTableHidingPlanner().plan(
                        registrationPlan,
                        false,
                        0L);
        NativeTextBuildKey key =
                NativeTextBuildKey.fromUtf8(
                        "runtime-reachability-source-test");
        HostJniCSourceGenerator generator =
                new HostJniCSourceGenerator();

        String conservative = generator.generate(
                implementationPlan,
                RuntimeLoaderPlan.create("native0", 0),
                methodTablePlan,
                key,
                key,
                key);
        String pruned = generator.generate(
                implementationPlan,
                RuntimeLoaderPlan.create("native0", 0),
                methodTablePlan,
                key,
                key,
                key,
                reachability);

        assertFalse(reachability.isConservative());
        assertTrue(reachability.families().isEmpty());
        assertTrue(conservative.contains("j2ll_rt_div_i32"));
        assertTrue(conservative.contains(
                "j2ll_rt_array_length_i32"));
        assertTrue(conservative.contains(
                "j2ll_var_handle_method_handle"));
        assertFalse(pruned.contains("j2ll_rt_div_i32"));
        assertFalse(pruned.contains(
                "j2ll_rt_array_length_i32"));
        assertFalse(pruned.contains("j2ll_rt_string_length"));
        assertFalse(pruned.contains(
                "j2ll_var_handle_method_handle"));
        assertFalse(pruned.contains(
                "j2ll_parameter_array_for_descriptor"));
        assertFalse(
                NativeLibcRequirementPlan.inspect(pruned).required(),
                "the exact scalar production surface should not retain libc");
        assertTrue(pruned.contains("RegisterNatives"));
        assertTrue(pruned.contains("JNI_OnLoad"));
        assertTrue(pruned.contains(
                implementationPlan.implementations().get(0)
                        .llvmFunctionSymbol()
                        .orElseThrow()));
    }
}

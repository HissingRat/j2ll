package xyz.melodysky.testsupport;

import java.nio.charset.StandardCharsets;
import xyz.melodysky.backend.llvm.LlvmModuleLowerer;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.ir.model.BusinessStringSymbolMapper;
import xyz.melodysky.ir.ssa.BytecodeToSsaLowerer;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.toolchain.NativeImplementationPlanner;
import xyz.melodysky.toolchain.HostJniCSourceGenerator;
import xyz.melodysky.toolchain.NativeImplementationPlan;
import xyz.melodysky.toolchain.initializer.InitializerImplementationPlanner;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Explicit deterministic protection identities for focused tests only. */
public final class TestProtectionMaterials {
    private static final byte[] BUILD_KEY =
            "j2ll-focused-test-build-identity-v1"
                    .getBytes(StandardCharsets.UTF_8);

    private TestProtectionMaterials() {}

    public static RuntimeTokenMapper runtimeTokens() {
        return RuntimeTokenMapper.fromBytes(BUILD_KEY);
    }

    public static BusinessStringSymbolMapper businessStringSymbols() {
        return BusinessStringSymbolMapper.fromBytes(BUILD_KEY);
    }

    public static NativeTextBuildKey nativeTextBuildKey() {
        return NativeTextBuildKey.fromBytes(BUILD_KEY);
    }

    public static BytecodeToSsaLowerer ssaLowerer() {
        return new BytecodeToSsaLowerer(runtimeTokens());
    }

    public static LlvmModuleLowerer llvmLowerer() {
        return new LlvmModuleLowerer(
                new LlvmNameMangler(),
                businessStringSymbols(),
                runtimeTokens());
    }

    public static NativeImplementationPlanner implementationPlanner() {
        return new NativeImplementationPlanner(
                new LlvmNameMangler(),
                businessStringSymbols(),
                runtimeTokens());
    }

    public static String hostJniSource(
            NativeImplementationPlan implementationPlan) {
        return new HostJniCSourceGenerator().generate(
                implementationPlan,
                nativeTextBuildKey());
    }

    public static InitializerImplementationPlanner initializerPlanner() {
        return new InitializerImplementationPlanner(runtimeTokens());
    }
}

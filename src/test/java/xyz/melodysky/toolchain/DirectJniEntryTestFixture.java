package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewritePlanner;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.NativeRegistrationPlanner;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Shared ASM-to-final-plan fixture for JNI proxy source tests. */
final class DirectJniEntryTestFixture {
    private DirectJniEntryTestFixture() {}

    static ParsedClass eligibleClass() {
        return parse(DirectJniEntryBytecodeFixture.eligibleClass());
    }

    static ParsedClass ineligibleClass() {
        return parse(DirectJniEntryBytecodeFixture.ineligibleClass());
    }

    static ParsedClass semanticClass() {
        return parse(
                SemanticJniProxyBytecodeFixture.OWNER,
                SemanticJniProxyBytecodeFixture.classBytes());
    }

    static Fixture fixture(
            ParsedClass parsedClass,
            List<String> methodNames) {
        return fixture(
                parsedClass,
                methodNames,
                NativeTextBuildKey.fromUtf8("direct-entry-source-test"));
    }

    static Fixture fixture(
            ParsedClass parsedClass,
            List<String> methodNames,
            NativeTextBuildKey buildKey) {
        List<MethodRewriteDecision> decisions =
                new MethodRewritePlanner().planClass(parsedClass).stream()
                        .filter(decision -> methodNames.contains(
                                decision.method().name()))
                        .toList();
        assertEquals(methodNames.size(), decisions.size());
        LinkedHashMap<String, IrMethod> irMethods = new LinkedHashMap<>();
        for (MethodRewriteDecision decision : decisions) {
            IrMethod irMethod = irMethod(
                    parsedClass,
                    decision.method().name());
            irMethods.put(irMethod.methodKey(), irMethod);
        }
        NativeRegistrationPlan registrations =
                new NativeRegistrationPlanner().plan(decisions);
        NativeImplementationPlan semanticPlan =
                new NativeImplementationPlanner().plan(
                        registrations,
                        decisions,
                        irMethods);
        NativeImplementationPlan implementationPlan =
                new NativeJniEntryFusionPlanner().plan(
                        semanticPlan,
                        irMethods,
                        buildKey);
        return new Fixture(implementationPlan, Map.copyOf(irMethods));
    }

    static String compile(Fixture fixture) throws Exception {
        NativeLlvmCompilation compilation = compileModel(fixture);
        return String.join("\n", compilation.textByOwner().values());
    }

    static NativeLlvmCompilation compileModel(Fixture fixture)
            throws Exception {
        return new NativeLlvmCompiler(
                        new LlvmModuleLowerer(),
                        new LlvmTextEmitter())
                .compile(
                        fixture.implementationPlan(),
                        fixture.irMethods(),
                        LlvmProtectionConfig.disabled(0x6aL));
    }

    static NativeMethodImplementation implementation(
            Fixture fixture,
            String methodName) {
        return fixture.implementationPlan().implementations().stream()
                .filter(candidate -> candidate.decision().method().name()
                        .equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private static ParsedClass parse(byte[] bytes) {
        return parse(DirectJniEntryBytecodeFixture.OWNER, bytes);
    }

    private static ParsedClass parse(String owner, byte[] bytes) {
        return new AsmClassParser()
                .parse(new ClassFileEntry(
                        owner + ".class",
                        bytes,
                        "direct-entry-fixture"))
                .artifact()
                .orElseThrow();
    }

    private static IrMethod irMethod(
            ParsedClass parsedClass,
            String name) {
        ParsedMethod method = parsedClass.methods().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        var cfgResult = new MethodCfgBuilder().build(method);
        var cfg = cfgResult.artifact().orElseThrow(() ->
                new IllegalStateException(
                        "CFG fixture failed for "
                                + method.methodKey()
                                + ": "
                                + cfgResult.diagnostics()));
        var lowering = new BytecodeToSsaLowerer().lower(cfg);
        SsaMethodResult result = lowering.artifact().orElseThrow(() ->
                new IllegalStateException(
                        "SSA fixture produced no result for "
                                + method.methodKey()
                                + ": "
                                + lowering.diagnostics()));
        return result.irMethod().orElseThrow(() ->
                new IllegalStateException(
                        "SSA fixture was skipped for "
                                + method.methodKey()
                                + " ["
                                + result.reasonCode()
                                + "]: "
                                + result.reason()
                                + "; diagnostics="
                                + lowering.diagnostics()));
    }

    record Fixture(
            NativeImplementationPlan implementationPlan,
            Map<String, IrMethod> irMethods) {}
}

package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.backend.llvm.LlvmFunctionAbi;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindSemantics;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;
import xyz.melodysky.backend.llvm.protection.LlvmBlockLayoutPerturbationResult;
import xyz.melodysky.backend.llvm.protection.LlvmCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.backend.llvm.protection.LlvmIrCallIndirectionResult;
import xyz.melodysky.backend.llvm.protection.LlvmOpaquePredicateResult;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.model.IrValue;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/** Small immutable fixture shared only by the direct-entry final-gate tests. */
final class NativeJniEntryTestFixture {
    static final String OWNER = "pkg/DirectGate";
    static final String OTHER_OWNER = "pkg/OtherGate";
    static final String LOGICAL_WRAPPER = "j2ll_test_logical_wrapper";
    static final String PROXY_ENTRY = "abcdefghijklmnopabcdefghijklmnop";
    static final String SEMANTIC_BODY = "j2ll_test_semantic_body";

    private NativeJniEntryTestFixture() {}

    static Fixture proxy() {
        IrMethod method = method();
        NativeMethodImplementation implementation = implementation(
                method,
                Optional.of(method));
        NativeJniEntryPlan entryPlan = NativeJniEntryPlan.llvmProxy(
                PROXY_ENTRY,
                LlvmFunctionAbi.physicalJniEntry(true),
                SEMANTIC_BODY,
                implementation.llvmFunctionAbi(),
                new NativeJniEntryTopology(
                        NativeJniEntryTopology.Shape.DIRECT_CANONICAL,
                        3,
                        List.of(),
                        List.of(),
                        0));
        NativeImplementationPlan plan = new NativeImplementationPlan(
                List.of(implementation),
                Map.of(),
                Map.of(),
                Map.of(method.methodKey(), entryPlan));
        return new Fixture(method, implementation, plan);
    }

    static Fixture proxyWithoutIrEvidence() {
        IrMethod method = method();
        NativeMethodImplementation implementation = implementation(
                method,
                Optional.empty());
        NativeJniEntryPlan entryPlan = NativeJniEntryPlan.llvmProxy(
                PROXY_ENTRY,
                LlvmFunctionAbi.physicalJniEntry(true),
                SEMANTIC_BODY,
                implementation.llvmFunctionAbi(),
                new NativeJniEntryTopology(
                        NativeJniEntryTopology.Shape.DIRECT_CANONICAL,
                        3,
                        List.of(),
                        List.of(),
                        0));
        NativeImplementationPlan plan = new NativeImplementationPlan(
                List.of(implementation),
                Map.of(),
                Map.of(),
                Map.of(method.methodKey(), entryPlan));
        return new Fixture(method, implementation, plan);
    }

    static Fixture wrapped() {
        IrMethod method = method();
        NativeMethodImplementation implementation = implementation(
                method,
                Optional.of(method));
        return new Fixture(
                method,
                implementation,
                new NativeImplementationPlan(List.of(implementation)));
    }

    static Fixture plannedProxy(NativeTextBuildKey buildKey) {
        IrMethod method = method();
        NativeMethodImplementation implementation = implementation(
                method,
                Optional.of(method));
        NativeImplementationPlan semanticPlan =
                new NativeImplementationPlan(List.of(implementation));
        NativeImplementationPlan plan = new NativeJniEntryFusionPlanner().plan(
                semanticPlan,
                Map.of(method.methodKey(), method),
                buildKey);
        return new Fixture(method, implementation, plan);
    }

    static Fixture plannedProxy(NativeJniEntryTopology.Shape shape) {
        for (int index = 0; index < 4096; index++) {
            Fixture fixture = plannedProxy(NativeTextBuildKey.fromUtf8(
                    "jni-entry-test-shape-" + index));
            if (fixture.plan()
                    .jniEntryPlanFor(fixture.method().methodKey())
                    .topology()
                    .orElseThrow()
                    .shape() == shape) {
                return fixture;
            }
        }
        throw new AssertionError("planner did not produce shape " + shape);
    }

    static LlvmModule synthesizedModule(Fixture fixture) {
        LlvmModule semantic = new LlvmModule(
                "jni-proxy-test",
                List.of(),
                List.of(semanticBody(fixture)));
        return new NativeJniProxySynthesizer().synthesize(
                OWNER,
                semantic,
                fixture.plan());
    }

    static LlvmFunction semanticBody(Fixture fixture) {
        List<LlvmParameter> parameters = List.of(
                new LlvmParameter(LlvmType.I32, "%arg0"),
                new LlvmParameter(LlvmType.I64, "%arg1"),
                new LlvmParameter(LlvmType.F64, "%arg2"));
        return new LlvmFunction(
                fixture.implementation().llvmFunctionSymbol().orElseThrow(),
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                parameters,
                List.of(new LlvmBasicBlock(
                        "entry",
                        List.of(),
                        new LlvmTerminator(
                                LlvmType.I32,
                                Optional.of("%arg0")))),
                LlvmNativeUnwindSemantics.PROVEN_ABSENT);
    }

    static NativeLlvmCompilation compilation(
            String owner,
            List<IrMethod> registeredMethods,
            LlvmModule module) {
        LlvmBlockLayoutPerturbationResult blockLayout =
                new LlvmBlockLayoutPerturbationResult(
                        module,
                        List.of(),
                        List.of());
        LlvmOpaquePredicateResult opaque =
                new LlvmOpaquePredicateResult(
                        module,
                        List.of(),
                        List.of());
        LlvmIrCallIndirectionResult irCalls =
                new LlvmIrCallIndirectionResult(
                        module,
                        List.of(),
                        List.of(),
                        List.of());
        LlvmCallIndirectionResult calls =
                new LlvmCallIndirectionResult(
                        module,
                        List.of(),
                        List.of(),
                        "TEST_DISABLED");
        LlvmGlobalLayoutResult globals =
                new LlvmGlobalLayoutResult(
                        module,
                        List.of(),
                        List.of());
        NativeLlvmModuleCompilation compiled =
                new NativeLlvmModuleCompilation(
                        owner,
                        registeredMethods,
                        registeredMethods,
                        blockLayout,
                        opaque,
                        irCalls,
                        calls,
                        globals,
                        "");
        return new NativeLlvmCompilation("direct-entry-gate-test", List.of(compiled));
    }

    private static IrMethod method() {
        IrValue input = new IrValue("%input", IrType.I32);
        IrValue wide = new IrValue("%wide", IrType.I64);
        IrValue decimal = new IrValue("%decimal", IrType.F64);
        return new IrMethod(
                OWNER,
                "wide",
                "(IJD)I",
                IrType.I32,
                List.of(input, wide, decimal),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnValue(input))));
    }

    private static NativeMethodImplementation implementation(
            IrMethod method,
            Optional<IrMethod> implementationIr) {
        ParsedMethod parsed = new ParsedMethod(
                method.owner(),
                method.name(),
                method.descriptor(),
                new AccessFlags(AccessFlags.PUBLIC | AccessFlags.STATIC),
                List.of(),
                List.of(),
                List.of(),
                true,
                5,
                1,
                new MethodNode(
                        Opcodes.ASM9,
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        method.name(),
                        method.descriptor(),
                        null,
                        null));
        MethodRewriteDecision decision = new MethodRewriteDecision(
                parsed,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                method.owner(),
                Optional.empty(),
                "test");
        NativeRegistrationEntry entry = new NativeRegistrationEntry(
                method.owner(),
                method.name(),
                method.descriptor(),
                LOGICAL_WRAPPER);
        return new NativeMethodImplementation(
                entry,
                decision,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.of(SEMANTIC_BODY),
                "TEST_PURE_SCALAR",
                false,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                implementationIr);
    }

    record Fixture(
            IrMethod method,
            NativeMethodImplementation implementation,
            NativeImplementationPlan plan) {}
}

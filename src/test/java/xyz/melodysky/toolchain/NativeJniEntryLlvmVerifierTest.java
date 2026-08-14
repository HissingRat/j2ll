package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmDeclaration;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmFunctionAttribute;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindSemantics;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

class NativeJniEntryLlvmVerifierTest {
    private final NativeJniEntryLlvmVerifier verifier =
            new NativeJniEntryLlvmVerifier();

    @Test
    void acceptsTheExactFinalProxyClosureForEveryPlannedShape() {
        for (NativeJniEntryTopology.Shape shape
                : NativeJniEntryTopology.Shape.values()) {
            NativeJniEntryTestFixture.Fixture fixture =
                    NativeJniEntryTestFixture.plannedProxy(shape);
            assertEquals(
                    List.of(),
                    validate(
                            fixture,
                            NativeJniEntryTestFixture.synthesizedModule(
                                    fixture)),
                    shape.name());
        }
    }

    @Test
    void rejectsAProxyThatCollapsesOntoItsSemanticBody() {
        NativeJniEntryTestFixture.Fixture fixture = directFixture();
        NativeJniEntryPlan entry = entry(fixture);
        LlvmModule collapsed = replaceFunction(
                module(fixture),
                entry.semanticBodySymbol().orElseThrow(),
                function -> copy(
                        function,
                        entry.functionSymbol(),
                        function.linkage(),
                        function.visibility(),
                        function.returnType(),
                        function.parameters(),
                        function.attributes(),
                        function.nativeUnwindSemantics()));

        assertIssues(
                fixture,
                collapsed,
                "LLVM_JNI_PROXY_DEFINITION_DUPLICATE",
                "LLVM_JNI_PROXY_SEMANTIC_BODY_DEFINITION_MISSING");
    }

    @Test
    void rejectsProxySemanticBodyAndBridgeSurfaceDrift() {
        NativeJniEntryTestFixture.Fixture fixture =
                NativeJniEntryTestFixture.plannedProxy(
                        NativeJniEntryTopology.Shape
                                .SINGLE_PERMUTING_BRIDGE);
        NativeJniEntryPlan entry = entry(fixture);
        String proxy = entry.functionSymbol();
        String body = entry.semanticBodySymbol().orElseThrow();
        String bridge = entry.topology().orElseThrow().bridgeSymbols().get(0);

        assertIssue(
                fixture,
                replaceFunction(module(fixture), proxy, function -> copy(
                        function,
                        function.name(),
                        LlvmLinkage.INTERNAL,
                        function.visibility(),
                        function.returnType(),
                        function.parameters(),
                        function.attributes(),
                        function.nativeUnwindSemantics())),
                "LLVM_JNI_PROXY_LINKAGE_MISMATCH");
        assertIssue(
                fixture,
                replaceFunction(module(fixture), body, function -> copy(
                        function,
                        function.name(),
                        function.linkage(),
                        LlvmVisibility.DEFAULT,
                        function.returnType(),
                        function.parameters(),
                        function.attributes(),
                        function.nativeUnwindSemantics())),
                "LLVM_JNI_PROXY_SEMANTIC_BODY_VISIBILITY_MISMATCH");
        assertIssue(
                fixture,
                replaceFunction(module(fixture), bridge, function -> copy(
                        function,
                        function.name(),
                        function.linkage(),
                        function.visibility(),
                        function.returnType(),
                        function.parameters(),
                        List.of(),
                        function.nativeUnwindSemantics())),
                "LLVM_JNI_PROXY_BRIDGE_NOINLINE_MISSING");
        assertIssue(
                fixture,
                replaceFunction(module(fixture), proxy, function -> copy(
                        function,
                        function.name(),
                        function.linkage(),
                        function.visibility(),
                        function.returnType(),
                        function.parameters(),
                        function.attributes(),
                        LlvmNativeUnwindSemantics.UNKNOWN)),
                "LLVM_JNI_PROXY_UNWIND_EVIDENCE_MISMATCH");
    }

    @Test
    void rejectsProxySemanticBodyAndBridgeAbiDrift() {
        NativeJniEntryTestFixture.Fixture fixture =
                NativeJniEntryTestFixture.plannedProxy(
                        NativeJniEntryTopology.Shape
                                .SINGLE_PERMUTING_BRIDGE);
        NativeJniEntryPlan entry = entry(fixture);
        String proxy = entry.functionSymbol();
        String body = entry.semanticBodySymbol().orElseThrow();
        String bridge = entry.topology().orElseThrow().bridgeSymbols().get(0);

        assertIssue(
                fixture,
                replaceFunction(module(fixture), proxy, function -> copy(
                        function,
                        function.name(),
                        function.linkage(),
                        function.visibility(),
                        LlvmType.I64,
                        function.parameters(),
                        function.attributes(),
                        function.nativeUnwindSemantics())),
                "LLVM_JNI_PROXY_RETURN_TYPE_MISMATCH");
        assertIssue(
                fixture,
                replaceFunction(module(fixture), body, function -> copy(
                        function,
                        function.name(),
                        function.linkage(),
                        function.visibility(),
                        function.returnType(),
                        replaceParameterType(function.parameters(), 0, LlvmType.I64),
                        function.attributes(),
                        function.nativeUnwindSemantics())),
                "LLVM_JNI_PROXY_SEMANTIC_BODY_PARAMETER_TYPE_MISMATCH");
        assertIssue(
                fixture,
                replaceFunction(module(fixture), bridge, function -> copy(
                        function,
                        function.name(),
                        function.linkage(),
                        function.visibility(),
                        function.returnType(),
                        replaceParameterType(function.parameters(), 0, LlvmType.PTR),
                        function.attributes(),
                        function.nativeUnwindSemantics())),
                "LLVM_JNI_PROXY_BRIDGE_PARAMETER_TYPE_MISMATCH");
    }

    @Test
    void rejectsResidualDeclarationsOwnerAndRegistrationDrift() {
        NativeJniEntryTestFixture.Fixture fixture = directFixture();
        NativeJniEntryPlan entry = entry(fixture);
        LlvmModule baseline = module(fixture);
        LlvmModule declaration = new LlvmModule(
                baseline.identifier(),
                List.of(new LlvmDeclaration(
                        entry.functionSymbol(),
                        "i32",
                        List.of("ptr", "ptr", "i32", "i64", "double"),
                        "test residual")),
                baseline.globals(),
                baseline.functions());
        assertIssue(
                fixture,
                declaration,
                "LLVM_JNI_PROXY_DECLARATION_RESIDUAL");

        assertContains(
                fixture,
                NativeJniEntryTestFixture.compilation(
                        NativeJniEntryTestFixture.OTHER_OWNER,
                        List.of(fixture.method()),
                        baseline),
                "LLVM_JNI_PROXY_OWNER_OR_MODULE_MISMATCH");
        assertContains(
                fixture,
                NativeJniEntryTestFixture.compilation(
                        NativeJniEntryTestFixture.OWNER,
                        List.of(),
                        baseline),
                "LLVM_JNI_PROXY_REGISTRATION_MODEL_MISMATCH");
    }

    private NativeJniEntryTestFixture.Fixture directFixture() {
        return NativeJniEntryTestFixture.plannedProxy(
                NativeJniEntryTopology.Shape.DIRECT_CANONICAL);
    }

    private NativeJniEntryPlan entry(
            NativeJniEntryTestFixture.Fixture fixture) {
        return fixture.plan().jniEntryPlanFor(fixture.method().methodKey());
    }

    private LlvmModule module(NativeJniEntryTestFixture.Fixture fixture) {
        return NativeJniEntryTestFixture.synthesizedModule(fixture);
    }

    private List<String> validate(
            NativeJniEntryTestFixture.Fixture fixture,
            LlvmModule module) {
        return verifier.validate(
                fixture.plan(),
                NativeJniEntryTestFixture.compilation(
                        NativeJniEntryTestFixture.OWNER,
                        List.of(fixture.method()),
                        module));
    }

    private void assertIssue(
            NativeJniEntryTestFixture.Fixture fixture,
            LlvmModule module,
            String reasonCode) {
        assertIssues(fixture, module, reasonCode);
    }

    private void assertIssues(
            NativeJniEntryTestFixture.Fixture fixture,
            LlvmModule module,
            String... reasonCodes) {
        List<String> issues = validate(fixture, module);
        for (String reasonCode : reasonCodes) {
            assertTrue(
                    issues.contains(fixture.method().methodKey()
                            + ":"
                            + reasonCode),
                    issues.toString());
        }
    }

    private void assertContains(
            NativeJniEntryTestFixture.Fixture fixture,
            NativeLlvmCompilation compilation,
            String reasonCode) {
        List<String> issues = verifier.validate(fixture.plan(), compilation);
        assertTrue(
                issues.contains(fixture.method().methodKey()
                        + ":"
                        + reasonCode),
                issues.toString());
    }

    private LlvmModule replaceFunction(
            LlvmModule module,
            String symbol,
            UnaryOperator<LlvmFunction> replacement) {
        ArrayList<LlvmFunction> functions = new ArrayList<>();
        boolean found = false;
        for (LlvmFunction function : module.functions()) {
            if (function.name().equals(symbol)) {
                functions.add(replacement.apply(function));
                found = true;
            } else {
                functions.add(function);
            }
        }
        assertTrue(found, "missing function " + symbol);
        return new LlvmModule(
                module.identifier(),
                module.declarations(),
                module.globals(),
                functions);
    }

    private LlvmFunction copy(
            LlvmFunction source,
            String name,
            LlvmLinkage linkage,
            LlvmVisibility visibility,
            LlvmType returnType,
            List<LlvmParameter> parameters,
            List<LlvmFunctionAttribute> attributes,
            LlvmNativeUnwindSemantics unwind) {
        return new LlvmFunction(
                name,
                linkage,
                visibility,
                returnType,
                parameters,
                source.blocks(),
                unwind,
                attributes);
    }

    private List<LlvmParameter> replaceParameterType(
            List<LlvmParameter> parameters,
            int index,
            LlvmType type) {
        ArrayList<LlvmParameter> replacement = new ArrayList<>(parameters);
        replacement.set(
                index,
                new LlvmParameter(type, parameters.get(index).name()));
        return List.copyOf(replacement);
    }
}

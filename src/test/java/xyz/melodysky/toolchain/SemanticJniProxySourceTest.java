package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmCallArgument;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmParameter;

/** Generated C and structured LLVM closure checks for 6B proxies. */
class SemanticJniProxySourceTest {
    private static final List<String> METHODS = List.of(
            "staticIdentity",
            "instanceIdentity",
            "intArrayIdentity",
            "objectArrayIdentity",
            "allocateObject",
            "allocateBytes",
            "readStaticField",
            "readInstanceField",
            "divide",
            "remainder",
            "callStringValueOf",
            "alwaysThrow");

    @Test
    void cRegistersOnlyProxyExternsAndLlvmKeepsSemanticBodiesSeparate()
            throws Exception {
        var fixture = fixture();
        String generatedC = new HostJniCSourceGenerator().generate(
                fixture.implementationPlan());
        NativeLlvmCompilation compilation =
                DirectJniEntryTestFixture.compileModel(fixture);
        LlvmModule module = compilation.modulesByOwner()
                .get(SemanticJniProxyBytecodeFixture.OWNER);

        for (String methodName : METHODS) {
            NativeMethodImplementation implementation =
                    DirectJniEntryTestFixture.implementation(
                            fixture,
                            methodName);
            NativeJniEntryPlan entry = fixture.implementationPlan()
                    .jniEntryPlanFor(implementation.methodKey());
            String proxy = entry.functionSymbol();
            String body = entry.semanticBodySymbol().orElseThrow();
            assertCExtern(generatedC, proxy);
            assertTrue(registrationTargets(generatedC, proxy), methodName);
            assertFalse(cFunctionDefinition(generatedC, proxy), methodName);
            assertFalse(registrationTargets(generatedC, body), methodName);
            assertFalse(generatedC.contains(body), methodName);
            for (String bridge : entry.topology()
                    .orElseThrow()
                    .bridgeSymbols()) {
                assertFalse(generatedC.contains(bridge), methodName);
            }
            assertTrue(function(module, proxy) != null, methodName);
            assertTrue(function(module, body) != null, methodName);
        }
    }

    @Test
    void proxyRoutesUseExactProjectedSsaArgumentsAndBridgesStaySemanticFree()
            throws Exception {
        var fixture = fixture();
        LlvmModule module = DirectJniEntryTestFixture.compileModel(fixture)
                .modulesByOwner()
                .get(SemanticJniProxyBytecodeFixture.OWNER);

        for (String methodName : METHODS) {
            NativeMethodImplementation implementation =
                    DirectJniEntryTestFixture.implementation(
                            fixture,
                            methodName);
            NativeJniEntryPlan entry = fixture.implementationPlan()
                    .jniEntryPlanFor(implementation.methodKey());
            NativeJniProxyAbiProjection projection =
                    NativeJniProxyAbiProjection.derive(implementation)
                            .orElseThrow();
            LlvmFunction proxy = function(module, entry.functionSymbol());
            assertEquals(
                    projection.physicalParameterTypes(),
                    proxy.parameters().stream()
                            .map(LlvmParameter::type)
                            .toList(),
                    methodName);
            List<LlvmParameter> canonical = projection
                    .semanticFromPhysicalIndices()
                    .stream()
                    .map(proxy.parameters()::get)
                    .toList();
            NativeJniEntryTopology topology = entry.topology().orElseThrow();
            for (int route = 0; route < 2; route++) {
                List<LlvmCallArgument> expected = topology
                        .parameterOrders()
                        .get(route)
                        .stream()
                        .map(canonical::get)
                        .map(parameter -> new LlvmCallArgument(
                                parameter.type(),
                                parameter.name()))
                        .toList();
                assertEquals(
                        expected,
                        proxy.blocks()
                                .get(route + 1)
                                .instructions()
                                .get(0)
                                .directCall()
                                .orElseThrow()
                                .arguments(),
                        methodName + ":route:" + route);
            }
            for (String bridge : topology.bridgeSymbols()) {
                assertSemanticFree(function(module, bridge), methodName);
            }
            assertSemanticFree(proxy, methodName);
        }
    }

    private DirectJniEntryTestFixture.Fixture fixture() {
        return DirectJniEntryTestFixture.fixture(
                DirectJniEntryTestFixture.semanticClass(),
                METHODS);
    }

    private LlvmFunction function(LlvmModule module, String symbol) {
        return module.functions().stream()
                .filter(candidate -> candidate.name().equals(symbol))
                .findFirst()
                .orElse(null);
    }

    private void assertSemanticFree(LlvmFunction function, String methodName) {
        Set<String> forbidden = Set.of(
                "JNIEnv",
                "DeleteLocalRef",
                "ExceptionCheck",
                "ExceptionOccurred",
                "ExceptionClear",
                "Throw");
        for (var block : function.blocks()) {
            for (LlvmInstruction instruction : block.instructions()) {
                String raw = instruction.rawText().orElse("");
                for (String marker : forbidden) {
                    assertFalse(raw.contains(marker),
                            methodName + ":" + function.name() + ":" + raw);
                }
                instruction.directCall().ifPresent(call -> {
                    for (String marker : forbidden) {
                        assertFalse(call.target().contains(marker),
                                methodName + ":" + function.name());
                    }
                });
            }
        }
    }

    private void assertCExtern(String source, String symbol) {
        assertTrue(
                Pattern.compile(
                                "(?m)^extern\\s+[^;\\n]+\\b"
                                        + Pattern.quote(symbol)
                                        + "\\([^;\\n]*\\);$")
                        .matcher(source)
                        .find(),
                symbol);
    }

    private boolean cFunctionDefinition(String source, String symbol) {
        return Pattern.compile(
                        "(?m)^static\\s+[^;\\n]+\\b"
                                + Pattern.quote(symbol)
                                + "\\([^;\\n]*\\)\\s*\\{$")
                .matcher(source)
                .find();
    }

    private boolean registrationTargets(String source, String symbol) {
        return Pattern.compile(
                        "(?m)\\.fnPtr\\s*=\\s*\\(void\\s*\\*\\)\\s*"
                                + Pattern.quote(symbol)
                                + "\\s*;")
                .matcher(source)
                .find();
    }
}

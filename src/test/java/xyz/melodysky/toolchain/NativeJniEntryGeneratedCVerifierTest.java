package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NativeJniEntryGeneratedCVerifierTest {
    private final NativeJniEntryGeneratedCVerifier verifier =
            new NativeJniEntryGeneratedCVerifier();

    @Test
    void rejectsAResidualCDefinitionForADirectLlvmEntry() {
        NativeJniEntryTestFixture.Fixture fixture =
                NativeJniEntryTestFixture.proxy();
        String physical = NativeJniEntryTestFixture.PROXY_ENTRY;
        String source = directExtern(physical)
                + "static jint "
                + physical
                + "(JNIEnv* env, jclass owner, jint arg0) { return arg0; }\n"
                + registration(physical);

        List<String> issues = verifier.verify(fixture.plan(), source);

        assertTrue(issues.contains(fixture.method().methodKey()
                + ":LLVM_JNI_PROXY_C_DEFINITION_RESIDUAL"), issues.toString());
    }

    @Test
    void rejectsTheOldLogicalWrapperSymbolOnTheDirectEntrySurface() {
        NativeJniEntryTestFixture.Fixture fixture =
                NativeJniEntryTestFixture.proxy();
        String source = directExtern(NativeJniEntryTestFixture.PROXY_ENTRY)
                + "void* residual = (void*)"
                + NativeJniEntryTestFixture.LOGICAL_WRAPPER
                + ";\n"
                + registration(NativeJniEntryTestFixture.PROXY_ENTRY);

        assertEquals(
                List.of(fixture.method().methodKey()
                        + ":LLVM_JNI_PROXY_LOGICAL_WRAPPER_RESIDUAL"),
                verifier.verify(fixture.plan(), source));
    }

    @Test
    void rejectsSemanticBodyAndBridgeReferencesLeakingBackIntoC() {
        NativeJniEntryTestFixture.Fixture fixture = fixtureWithBridges();
        NativeJniEntryPlan entry = fixture.plan()
                .jniEntryPlanFor(fixture.method().methodKey());
        String baseline = directExtern(entry.functionSymbol())
                + registration(entry.functionSymbol());

        assertEquals(
                List.of(fixture.method().methodKey()
                        + ":LLVM_JNI_PROXY_SEMANTIC_BODY_C_REFERENCE_RESIDUAL"),
                verifier.verify(
                        fixture.plan(),
                        baseline
                                + "void* semantic = (void*)"
                                + entry.semanticBodySymbol().orElseThrow()
                                + ";\n"));
        assertEquals(
                List.of(fixture.method().methodKey()
                        + ":LLVM_JNI_PROXY_BRIDGE_C_REFERENCE_RESIDUAL"),
                verifier.verify(
                        fixture.plan(),
                        baseline
                                + "void* bridge = (void*)"
                                + entry.topology()
                                        .orElseThrow()
                                        .bridgeSymbols()
                                        .get(0)
                                + ";\n"));
    }

    @Test
    void rejectsAWrappedEntryWhosePhysicalCDefinitionDisappeared() {
        NativeJniEntryTestFixture.Fixture fixture =
                NativeJniEntryTestFixture.wrapped();
        String source = registration(
                NativeJniEntryTestFixture.LOGICAL_WRAPPER);

        assertEquals(
                List.of(fixture.method().methodKey()
                        + ":WRAPPED_JNI_ENTRY_C_DEFINITION_MISSING"),
                verifier.verify(fixture.plan(), source));
    }

    private String directExtern(String symbol) {
        return "extern jint "
                + symbol
                + "(JNIEnv*, jclass, jint, jlong, jdouble);\n";
    }

    private String registration(String symbol) {
        return "binding.fnPtr = (void *) " + symbol + ";\n";
    }

    private NativeJniEntryTestFixture.Fixture fixtureWithBridges() {
        for (int index = 0; index < 256; index++) {
            NativeJniEntryTestFixture.Fixture fixture =
                    NativeJniEntryTestFixture.plannedProxy(
                            xyz.melodysky.toolchain.nativetext
                                    .NativeTextBuildKey.fromUtf8(
                                            "generated-c-bridge-" + index));
            if (!fixture.plan()
                    .jniEntryPlanFor(fixture.method().methodKey())
                    .topology()
                    .orElseThrow()
                    .bridgeSymbols()
                    .isEmpty()) {
                return fixture;
            }
        }
        throw new AssertionError("planner did not produce a proxy bridge");
    }
}

package xyz.melodysky.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.ParsedClass;

/** Generated-C and final-LLVM surface contract for the 6A proxy slice. */
class DirectJniEntryFusionSourceTest {
    @Test
    void ordinaryWideScalarEntriesUseDistinctLlvmProxiesWithoutCWrappers()
            throws Exception {
        ParsedClass parsedClass = DirectJniEntryTestFixture.eligibleClass();
        List<String> methods = List.of(
                "staticVoid",
                "staticInt",
                "staticLong",
                "staticFloat",
                "staticDouble",
                "instanceInt",
                "instanceDouble");
        DirectJniEntryTestFixture.Fixture fixture =
                DirectJniEntryTestFixture.fixture(parsedClass, methods);

        String generatedC = new HostJniCSourceGenerator().generate(
                fixture.implementationPlan());
        String llvm = DirectJniEntryTestFixture.compile(fixture);

        for (String methodName : methods) {
            NativeMethodImplementation implementation =
                    DirectJniEntryTestFixture.implementation(
                            fixture,
                            methodName);
            assertEquals(
                    NativeImplementationPath.LLVM_NATIVE_PATH,
                    implementation.path());
            NativeJniEntryPlan entryPlan = fixture.implementationPlan()
                    .jniEntryPlanFor(implementation.methodKey());
            assertTrue(entryPlan.llvmJniProxy(), methodName);
            assertTrue(entryPlan.physicalLlvmAbi().passesJniEnv(), methodName);
            assertEquals(
                    implementation.decision().method().accessFlags().isStatic(),
                    entryPlan.physicalLlvmAbi().passesOwnerClass(),
                    methodName);
            assertEquals(
                    implementation.llvmFunctionAbi(),
                    entryPlan.semanticLlvmAbi(),
                    methodName);
            assertFalse(
                    entryPlan.semanticLlvmAbi().isPhysicalJniEntry(),
                    methodName);
            String proxySymbol = entryPlan.functionSymbol();
            String semanticSymbol = entryPlan.semanticBodySymbol()
                    .orElseThrow();
            assertEquals(
                    implementation.llvmFunctionSymbol().orElseThrow(),
                    semanticSymbol,
                    methodName);
            assertNotEquals(proxySymbol, semanticSymbol, methodName);
            String declaration = cDeclaration(generatedC, proxySymbol);
            assertTrue(
                    declaration.contains(
                            (implementation.decision().method().accessFlags().isStatic()
                                            ? "JNIEnv*, jclass"
                                            : "JNIEnv*, jobject")),
                    () -> methodName + " has the wrong physical JNI receiver ABI\n"
                            + generatedC);
            assertFalse(
                    cFunctionDefinition(generatedC, proxySymbol),
                    () -> methodName
                            + " must register an LLVM proxy, not a generated C wrapper\n"
                            + generatedC);
            assertTrue(
                    registrationTargets(generatedC, proxySymbol),
                    () -> methodName
                            + " must register the distinct LLVM proxy\n"
                            + generatedC);
            assertFalse(
                    registrationTargets(generatedC, semanticSymbol),
                    () -> methodName
                            + " must not register the semantic LLVM body directly\n"
                            + generatedC);
            assertFalse(
                    generatedC.contains(implementation.entry().nativeSymbol()),
                    () -> methodName
                            + " must not retain its logical C wrapper symbol\n"
                            + generatedC);
            for (String bridgeSymbol : entryPlan.topology()
                    .orElseThrow()
                    .bridgeSymbols()) {
                assertFalse(
                        generatedC.contains(bridgeSymbol),
                        () -> methodName
                                + " must keep LLVM proxy bridges out of generated C\n"
                                + generatedC);
            }
            assertTrue(
                    llvm.contains("@" + proxySymbol + "("),
                    () -> methodName
                            + " must define the registered proxy in LLVM\n"
                            + llvm);
            assertTrue(
                    llvm.contains("@" + semanticSymbol + "("),
                    () -> methodName
                            + " must retain a distinct semantic LLVM body\n"
                            + llvm);
        }
    }

    @Test
    void narrowReferenceAndSemanticSurfaceMethodsRetainCWrappers()
            throws Exception {
        ParsedClass parsedClass = DirectJniEntryTestFixture.ineligibleClass();
        List<String> methods = List.of(
                "narrowBoolean",
                "narrowByte",
                "narrowChar",
                "narrowShort",
                "referenceIdentity",
                "readField",
                "divide",
                "alwaysThrow",
                "callee",
                "caller");
        DirectJniEntryTestFixture.Fixture fixture =
                DirectJniEntryTestFixture.fixture(parsedClass, methods);

        String generatedC = new HostJniCSourceGenerator().generate(
                fixture.implementationPlan());
        String llvm = DirectJniEntryTestFixture.compile(fixture);

        for (String methodName : methods) {
            NativeMethodImplementation implementation =
                    DirectJniEntryTestFixture.implementation(
                            fixture,
                            methodName);
            assertEquals(
                    NativeImplementationPath.LLVM_NATIVE_PATH,
                    implementation.path(),
                    methodName);
            NativeJniEntryPlan entryPlan = fixture.implementationPlan()
                    .jniEntryPlanFor(implementation.methodKey());
            assertFalse(entryPlan.llvmJniProxy(), methodName);
            String nativeSymbol = entryPlan.functionSymbol();
            assertTrue(
                    cFunctionDefinition(generatedC, nativeSymbol),
                    () -> methodName
                            + " is outside the 6A proof slice and must retain its C wrapper\n"
                            + generatedC);
            assertFalse(
                    llvm.contains("@" + nativeSymbol + "("),
                    () -> methodName
                            + " must not masquerade as a direct physical JNI entry\n"
                            + llvm);
        }
    }

    private String cDeclaration(String source, String symbol) {
        var matcher = Pattern.compile(
                        "(?m)^extern\\s+[^;\\n]+\\b"
                                + Pattern.quote(symbol)
                                + "\\([^;\\n]*\\);$")
                .matcher(source);
        assertTrue(
                matcher.find(),
                () -> "missing exact C declaration for " + symbol + "\n" + source);
        return matcher.group();
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
